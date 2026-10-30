"""可插拔偏好提炼契约。

[build_distill_provider] 优先装配 LLM 后端；一旦 LLM 缺 key、超时或返回空，
[FallbackDistillProvider] 会自动回落规则启发式，保证 /memory/distill 永不失败。
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class DistillProvider(ABC):
    """从对话轮次中抽取**长期稳定的用户偏好/关键事实**。"""

    @property
    @abstractmethod
    def name(self) -> str:
        """后端标识，用于 /healthz 自检与日志。"""

    @abstractmethod
    def distill(self, turns: list[str]) -> list[str]:
        """返回去重后的偏好短句列表，可能为空。"""


class FallbackDistillProvider(DistillProvider):
    """主提炼器失败或返回空时自动回落，确保提炼链路绝不中断主流程。"""

    def __init__(self, primary: DistillProvider, fallback: DistillProvider):
        self._primary = primary
        self._fallback = fallback

    @property
    def name(self) -> str:
        return f"{self._primary.name}|fallback:{self._fallback.name}"

    def distill(self, turns: list[str]) -> list[str]:
        try:
            result = self._primary.distill(turns)
            if result:
                return result
        except Exception:
            # 提炼是增强能力，任何远端异常都必须吞掉并降级
            pass
        return self._fallback.distill(turns)


def build_distill_provider(
    llm_base_url: str,
    llm_api_key: str,
    llm_model: str,
) -> DistillProvider:
    from .rule import RuleDistillProvider

    rule = RuleDistillProvider()
    if llm_base_url and llm_api_key:
        from .llm import LLMDistillProvider

        return FallbackDistillProvider(
            LLMDistillProvider(llm_base_url, llm_api_key, llm_model), rule
        )
    return rule
