/**
 * Represents a graded evaluation item with its weight and status.
 * Used to group grades according to platform structure (N1, N2, Exam).
 */
data class SgaGradeGroup(
    val name: String, // e.g., "N1", "N2", "EXAM"
    val weight: Int, // Weight percentage (35, 35, 30)
    val obtained: Double?, // Obtained score
    val max: Double, // Maximum score for this group
    val status: String // "graded", "submitted", "pending", etc.
)

/**
 * Represents the grouped grade structure for a course activity.
 * Contains the three main components: N1, N2, and Exam (final).
 */
data class CourseGradeStructure(
    val name: String, // Course name
    val n1: SgaGradeGroup?, // First partial (N1)
    val n2: SgaGradeGroup?, // Second partial (N2)
    val exam: SgaGradeGroup?, // Final exam (Exam)
    val totalWeight: Int = 100, // Total weight of all groups
    val overallGrade: Double? = null, // Calculated overall grade
    val overallMaxGrade: Double = 100.0
)