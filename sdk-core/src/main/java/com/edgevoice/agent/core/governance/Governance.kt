package com.edgevoice.agent.core.governance

/**
 * 企业级治理四件套的聚合入口，便于上层一次性注入与统一观测。
 */
class Governance(
    val sanitizer: SecuritySanitizer = SecuritySanitizer(),
    val tokenBudget: TokenBudget = TokenBudget(),
    val iterationGuard: MaxIterationGuard = MaxIterationGuard(),
    val circuitBreaker: CircuitBreaker = CircuitBreaker(),
)
