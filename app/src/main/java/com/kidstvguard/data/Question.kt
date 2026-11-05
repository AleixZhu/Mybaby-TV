package com.kidstvguard.data

/**
 * 一道题。
 *
 * @param stem     题干
 * @param options  选项（长度 2..4）
 * @param answer   正确答案索引（0..options.size-1）
 * @param explain  答错时展示的解释，可空
 * @param grades   适用年级集合。空 = 通用题（所有年级都会命中）；
 *                 非空 = 只有当前选中年级在此集合中才使用。
 *                 JSON 里可写单值 "grade": 3 或多值 "grades": [1,2,3]，
 *                 Repository 解析时统一归入 [grades]。
 */
data class Question(
    val stem: String,
    val options: List<String>,
    val answer: Int,
    val explain: String? = null,
    val grades: Set<Int> = emptySet()
) {
    /** 当前年级是否可用此题。grade=0 表示"不区分"，所有题都命中。 */
    fun matchesGrade(grade: Int): Boolean {
        if (grade <= 0) return true
        if (grades.isEmpty()) return true
        return grades.contains(grade)
    }
}
