"""LLM 提炼：OpenAI 兼容 Chat Completions，用于抽取高精度长期偏好。

无 key 时由 [build_distill_provider] 直接跳过本后端；本类内部任何异常都会抛出，
交由 [FallbackDistillProvider] 回落规则提炼。
"""

from __future__ import annotations

import json

from . import DistillProvider

_MAX_TURNS = 12

_SYSTEM_PROMPT = (
    "你是长期记忆提炼器。请从对话中只抽取用户**长期稳定的偏好或关键事实**"
    "（例如口味、称呼、惯用习惯、禁忌、常用设定），忽略一次性、时效性或 AI 自己说的话。"
    "输出严格 JSON 数组，每个元素是一条简短中文短句，最多 5 条；没有则输出 []。"
    "只输出 JSON，不要任何解释。"
)


class LLMDistillProvider(DistillProvider):
    def __init__(
        self,
        base_url: str,
        api_key: str,
        model: str,
        timeout: float = 15.0,
    ):
        self._endpoint = base_url.rstrip("/") + "/chat/completions"
        self._api_key = api_key
        self._model = model
        self._timeout = timeout

    @property
    def name(self) -> str:
        return f"llm:{self._model}"

    def distill(self, turns: list[str]) -> list[str]:
        import httpx

        dialogue = "\n".join(t for t in turns[-_MAX_TURNS:] if t)
        response = httpx.post(
            self._endpoint,
            headers={"Authorization": f"Bearer {self._api_key}"},
            json={
                "model": self._model,
                "temperature": 0.2,
                "messages": [
                    {"role": "system", "content": _SYSTEM_PROMPT},
                    {"role": "user", "content": dialogue},
                ],
            },
            timeout=self._timeout,
        )
        response.raise_for_status()
        content = response.json()["choices"][0]["message"]["content"]
        return self._parse(content)

    @staticmethod
    def _parse(content: str) -> list[str]:
        """容错解析：剥掉可能的 ```json 围栏，只接受 JSON 字符串数组。"""
        text = content.strip()
        if text.startswith("```"):
            text = text.strip("`")
            text = text[text.find("[") :] if "[" in text else text
        parsed = json.loads(text)
        if not isinstance(parsed, list):
            return []
        return [str(item).strip() for item in parsed if str(item).strip()]
