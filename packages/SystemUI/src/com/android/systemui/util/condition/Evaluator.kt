package com.android.systemui.util.condition

import android.annotation.IntDef

/**
 * Helper for evaluating a collection of [Condition] objects with a given
 * [Evaluator.ConditionOperand]
 */
internal object Evaluator {
    /** Operands for combining multiple conditions together */
    @Retention(AnnotationRetention.SOURCE)
    @IntDef(value = [OP_AND, OP_OR])
    annotation class ConditionOperand

    /**
     * 3-valued logical AND operand, with handling for unknown values (represented as null)
     *
     * ```
     * +-----+----+---+---+
     * | AND | T  | F | U |
     * +-----+----+---+---+
     * | T   | T  | F | U |
     * | F   | F  | F | F |
     * | U   | U  | F | U |
     * +-----+----+---+---+
     * ```
     */
    const val OP_AND = 0

    /**
     * 3-valued logical OR operand, with handling for unknown values (represented as null)
     *
     * ```
     * +-----+----+---+---+
     * | OR  | T  | F | U |
     * +-----+----+---+---+
     * | T   | T  | T | T |
     * | F   | T  | F | U |
     * | U   | T  | U | U |
     * +-----+----+---+---+
     * ```
     */
    const val OP_OR = 1

    /**
     * Evaluates a set of conditions with a given operand
     *
     * If overriding conditions are present, they take precedence over normal conditions if set.
     *
     * @param conditions The collection of conditions to evaluate. If empty, null is returned.
     * @param operand The operand to use when evaluating.
     * @return Either true or false if the value is known, or null if value is unknown
     */
    fun evaluate(conditions: Collection<Condition>, @ConditionOperand operand: Int): Boolean? {
        if (conditions.isEmpty()) return null
        // If there are overriding conditions with values set, they take precedence.
        val targetConditions =
            conditions
                .filter { it.isConditionSet && it.isOverridingCondition }
                .ifEmpty { conditions }
        return when (operand) {
            OP_AND ->
                threeValuedAndOrOr(conditions = targetConditions, returnValueIfAnyMatches = false)
            OP_OR ->
                threeValuedAndOrOr(conditions = targetConditions, returnValueIfAnyMatches = true)
            else -> null
        }
    }

    /**
     * Helper for evaluating 3-valued logical AND/OR.
     *
     * @param returnValueIfAnyMatches AND returns false if any value is false. OR returns true if
     * any value is true.
     */
    private fun threeValuedAndOrOr(
        conditions: Collection<Condition>,
        returnValueIfAnyMatches: Boolean
    ): Boolean? {
        var hasUnknown = false
        for (condition in conditions) {
            if (!condition.isConditionSet) {
                hasUnknown = true
                continue
            }
            if (condition.isConditionMet == returnValueIfAnyMatches) {
                return returnValueIfAnyMatches
            }
        }
        return if (hasUnknown) null else !returnValueIfAnyMatches
    }
}
