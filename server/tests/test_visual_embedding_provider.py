"""HTTP contract tests only; fake vectors are never retrieval quality evidence."""
import io
import json
import math

import httpx
from PIL import Image
import pytest

from app.visual_retrieval.providers import DashscopeMultimodalEmbeddingProvider, EmbeddingError, normalise_vector


DIM = 256


def vector(first=1.0):
    return [first, 1.0] + [0.0] * (DIM - 2)


def provider(handler, **kwargs):
    return DashscopeMultimodalEmbeddingProvider("secret-never-in-errors", dimension=DIM,
        transport=httpx.MockTransport(handler), **kwargs)


def jpeg():
    stream = io.BytesIO()
    Image.new("RGB", (4, 4), "blue").save(stream, "JPEG")
    return stream.getvalue()


def test_response_mapping_uses_index_not_vl_type_and_batches():
    seen = []
    def handle(request):
        body = json.loads(request.content)
        seen.append(body)
        assert request.headers["authorization"] == "Bearer secret-never-in-errors"
        assert body["parameters"] == {"dimension": DIM, "enable_fusion": False, "output_type": "dense"}
        rows = [{"index": i, "type": "vl", "embedding": vector(int(item["text"]))}
                for i, item in enumerate(body["input"]["contents"])]
        return httpx.Response(200, json={"output": {"embeddings": list(reversed(rows))}})
    p = provider(handle, batch_size=2)
    try:
        rows = p.encode_texts(["1", "2", "3"])
        assert [len(b["input"]["contents"]) for b in seen] == [2, 1]
        assert rows[0][0] < rows[1][0] < rows[2][0]
        assert all(math.isclose(sum(x*x for x in row), 1.0) for row in rows)
    finally:
        p.close()


def test_image_is_data_uri_and_empty_batch_never_calls_network():
    seen = []
    def handle(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json={"output": {"embeddings": [{"index": 0, "type": "vl", "embedding": vector()}]}})
    p = provider(handle)
    assert p.encode_texts([]) == []
    assert p.encode_images([]) == []
    p.encode_images([jpeg()])
    assert seen[0]["input"]["contents"][0]["image"].startswith("data:image/jpeg;base64,")
    p.close()


@pytest.mark.parametrize("embeddings,code", [
    ([], "embedding_result_count"),
    ([{"index": 0, "type": "vl", "embedding": vector()}] * 2, "embedding_result_count"),
    ([{"index": True, "type": "vl", "embedding": vector()}], "embedding_invalid_index"),
    ([{"index": -1, "type": "vl", "embedding": vector()}], "embedding_invalid_index"),
    ([{"index": 1, "type": "vl", "embedding": vector()}], "embedding_invalid_index"),
    ([{"index": 0, "type": "fusion", "embedding": vector()}], "embedding_invalid_type"),
    ([{"index": 0, "type": "vl", "embedding": [1.0]}], "embedding_invalid_dimension"),
    ([{"index": 0, "type": "vl", "embedding": [0.0] * DIM}], "embedding_zero_vector"),
    ([{"index": 0, "type": "vl", "embedding": [True] * DIM}], "embedding_invalid_vector"),
    ([{"index": 0, "type": "vl", "embedding": ["1"] * DIM}], "embedding_invalid_vector"),
])
def test_malformed_vectors_fail_closed(embeddings, code):
    p = provider(lambda _: httpx.Response(200, json={"output": {"embeddings": embeddings}}))
    with pytest.raises(EmbeddingError, match=code):
        p.encode_texts(["钥匙"])
    p.close()


def test_duplicate_indexes_rejected_even_when_count_matches():
    p = provider(lambda _: httpx.Response(200, json={"output": {"embeddings":
        [{"index": 0, "type": "vl", "embedding": vector()}] * 2}}))
    with pytest.raises(EmbeddingError, match="embedding_invalid_index"):
        p.encode_texts(["钥匙", "台灯"])
    p.close()


@pytest.mark.parametrize("value", [float("nan"), float("inf"), -float("inf")])
def test_nonfinite_vectors_are_rejected(value):
    body = json.dumps({"output": {"embeddings": [{"index": 0, "type": "vl", "embedding": vector(value)}]}})
    p = provider(lambda _: httpx.Response(200, content=body))
    with pytest.raises(EmbeddingError, match="embedding_invalid_vector"):
        p.encode_texts(["x"])
    p.close()


def test_redirect_not_followed_and_error_body_not_exposed():
    seen = []
    def handle(request):
        seen.append(str(request.url))
        return httpx.Response(302, headers={"Location": "https://attacker.invalid"},
                              text="secret-never-in-errors private note")
    p = provider(handle)
    with pytest.raises(EmbeddingError) as result:
        p.encode_texts(["private user note"])
    assert str(result.value) == "embedding_http_error"
    assert len(seen) == 1
    p.close()


def test_transport_error_redacted():
    def handle(request):
        raise httpx.ReadTimeout("secret-never-in-errors private note", request=request)
    p = provider(handle)
    with pytest.raises(EmbeddingError) as result:
        p.encode_texts(["private user note"])
    assert str(result.value) == "embedding_timeout"
    assert result.value.__cause__ is None
    p.close()


@pytest.mark.parametrize("first_status", [429, 503])
def test_one_transient_http_failure_is_retried(first_status):
    calls = 0
    def handle(request):
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(first_status, text="private upstream detail")
        return httpx.Response(200, json={"output": {"embeddings": [
            {"index": 0, "type": "vl", "embedding": vector()},
        ]}})
    p = provider(handle)
    try:
        assert len(p.encode_texts(["钥匙"])) == 1
        assert calls == 2
    finally:
        p.close()


def test_authentication_failure_does_not_retry_or_expose_body():
    calls = 0
    def handle(request):
        nonlocal calls
        calls += 1
        return httpx.Response(401, text="private upstream detail")
    p = provider(handle)
    try:
        with pytest.raises(EmbeddingError) as error:
            p.encode_texts(["钥匙"])
        assert error.value.code == "embedding_auth_failed"
        assert "private" not in str(error.value)
        assert calls == 1
    finally:
        p.close()


def test_namespace_changes_with_dimension_instruction_and_endpoint():
    response = lambda _: httpx.Response(500)
    a = provider(response)
    b = provider(response, instruct="Retrieve objects.")
    c = provider(response, endpoint="https://other.invalid/embedding")
    d = DashscopeMultimodalEmbeddingProvider("different-secret", dimension=512, transport=httpx.MockTransport(response))
    same = DashscopeMultimodalEmbeddingProvider("different-secret", dimension=DIM, transport=httpx.MockTransport(response))
    assert a.namespace == same.namespace
    assert len({p.namespace for p in (a, b, c, d)}) == 4
    for p in (a, b, c, d, same):
        p.close()


@pytest.mark.parametrize("endpoint", ["http://localhost/x", "https://key:secret@example.org/x", "https://example.org/x?key=s"])
def test_only_safe_configured_https_endpoints(endpoint):
    with pytest.raises(ValueError):
        provider(lambda _: httpx.Response(500), endpoint=endpoint)


def test_inputs_are_bounded_and_images_decoded_before_http():
    p = provider(lambda _: pytest.fail("invalid input reached network"))
    for operation in (lambda: p.encode_texts([" "]), lambda: p.encode_texts(["x"] * 101),
                      lambda: p.encode_images([b"not jpeg"]), lambda: p.encode_texts(["x" * 16001])):
        with pytest.raises(EmbeddingError):
            operation()
    p.close()


def test_normalisation_does_not_overflow_or_underflow_finite_vectors():
    for values in ([1e308, 1e308], [1e-308, 1e-308]):
        actual = normalise_vector(values, 2)
        assert actual == pytest.approx([math.sqrt(.5), math.sqrt(.5)])
