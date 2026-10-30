"""Bounded, inspectable query expansion. Original words and time filters survive."""
from __future__ import annotations

import re
import unicodedata

SYNONYMS = (("充电头", "电源适配器", "充电器"), ("台灯", "桌面照明灯"),
            ("遥控板", "遥控器"), ("水杯", "杯子"), ("移动电源", "充电宝"))
VERSION = "query-v1-original-plus-two"


def variants(query: str, raw_query: str | None = None) -> list[str]:
    values = []
    for value in (raw_query, query):
        if value and value.strip() not in values:
            values.append(value.strip())
    for group in SYNONYMS:
        for word in group:
            if word in query:
                for alternative in group:
                    candidate = query.replace(word, alternative)
                    if candidate not in values:
                        values.append(candidate)
    return values[:3]


def tokens(text: str) -> list[str]:
    """Chinese bigrams retain short object names; Latin model numbers stay whole.

    This is deliberately not advertised as a linguistic Chinese segmenter.
    Quoted FTS terms never interpolate user syntax into MATCH expressions.
    """
    text = unicodedata.normalize("NFKC", text).casefold()
    out = []
    for part in re.findall(r"[\u4e00-\u9fff]+|[a-z0-9_]+", text):
        if re.fullmatch(r"[\u4e00-\u9fff]+", part):
            out.extend(part[i:i + 2] for i in range(len(part) - 1))
            if len(part) == 1:
                out.append(part)
        else:
            out.append(part)
    return list(dict.fromkeys(out))[:256]


def record_text(record: dict) -> str:
    return " ".join([record["object_name"], *record["aliases"], record["location"], record["note"]])


def rrf(channels: dict[str, list[str]], limit: int = 100) -> list[tuple[str, float]]:
    scores: dict[str, float] = {}
    for ids in channels.values():
        for rank, record_id in enumerate(dict.fromkeys(ids), 1):
            scores[record_id] = scores.get(record_id, 0.0) + 1.0 / (60 + rank)
    return sorted(scores.items(), key=lambda item: (-item[1], item[0]))[:limit]
