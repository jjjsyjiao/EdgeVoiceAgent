"""规则启发式提炼：零依赖、永不失败，作为 LLM 后端的兜底。

策略：按句切分，命中「第一人称偏好句式」或「显式记忆指令」的整句即抽取，随后去重。
刻意要求第一人称锚点（我 / I），以降低把 AI 回复误当用户偏好的概率。
"""

from __future__ import annotations

import re

from . import DistillProvider

_MAX_ITEMS = 8
_MAX_LEN = 120

_PATTERNS = tuple(
    re.compile(p, re.IGNORECASE)
    for p in (
        r"记住|记一下|记下|记牢|记着",
        r"我\s*(?:更|最|比较|特别|有点)?\s*(?:喜欢|不喜欢|偏爱|偏好|讨厌|习惯|倾向|想要|爱)",
        r"我\s*(?:的)?\s*(?:口味|风格|习惯|名字|称呼|名字叫)",
        r"\bi\s+(?:like|love|prefer|hate|dislike|always|never|usually)\b",
        r"\b(?:remember|note that)\b",
    )
)


class RuleDistillProvider(DistillProvider):
    @property
    def name(self) -> str:
        return "rule"

    def distill(self, turns: list[str]) -> list[str]:
        out: list[str] = []
        for turn in turns:
            for sentence in re.split(r"[。！？!?\n]", turn or ""):
                text = sentence.strip()
                if not text or len(text) > _MAX_LEN:
                    continue
                if any(p.search(text) for p in _PATTERNS) and text not in out:
                    out.append(text)
                if len(out) >= _MAX_ITEMS:
                    return out
        return out
