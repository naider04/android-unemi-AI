package com.example.data.network

import org.json.JSONObject

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

/**
 * Helper functions for grade processing
 */
object GradeUtils {
    /**
     * Extracts grouped grades from raw grade items according to platform structure.
     * Returns grouped grades with proper weights and statuses.
     */
    fun extractGroupedGrades(
        courseName: String,
        gradeItems: List<JSONObject>
    ): CourseGradeStructure {
        var n1: SgaGradeGroup? = null
        var n2: SgaGradeGroup? = null
        var exam: SgaGradeGroup? = null
        
        for (gradeItem in gradeItems) {
            val itemType = gradeItem.optString("itemmodule", "")
            val itemInstance = gradeItem.optInt("iteminstance", -1)
            val graderaw = gradeItem.optString("graderaw", "")
            val grademax = gradeItem.optString("grademax", "10")
            val gradedatesubmitted = gradeItem.optLong("gradedatesubmitted", 0L)
            val gradedategraded = gradeItem.optLong("gradedategraded", 0L)
            
            // Determine the type based on item module and name
            val evalName = when (itemType) {
                "quiz" -> {
                    // Check if it's an exam based on name
                    val name = gradeItem.optString("itemname", "")
                    if (name.contains("EXAMEN_FINAL", ignoreCase = true) || 
                        name.contains("EXAMEN_RECUPERACION", ignoreCase = true) ||
                        name.contains("EXAM", ignoreCase = true)) {
                        "EXAM"
                    } else if (name.contains("N1", ignoreCase = true)) {
                        "N1"
                    } else if (name.contains("N2", ignoreCase = true)) {
                        "N2"
                    } else if (name.contains("TEST", ignoreCase = true)) {
                        "N1" // Assuming tests are part of N1
                    } else {
                        "N1" // Default to N1 for quizzes
                    }
                }
                "assign" -> {
                    // Check if it's an exam based on name
                    val name = gradeItem.optString("itemname", "")
                    if (name.contains("EXAMEN_FINAL", ignoreCase = true) || 
                        name.contains("EXAMEN_RECUPERACION", ignoreCase = true) ||
                        name.contains("EXAM", ignoreCase = true)) {
                        "EXAM"
                    } else if (name.contains("N1", ignoreCase = true)) {
                        "N1"
                    } else if (name.contains("N2", ignoreCase = true)) {
                        "N2"
                    } else {
                        "N1" // Default to N1 for assignments
                    }
                }
                else -> "N1" // Default to N1 for other types
            }
            
            // Determine weight based on type
            val weight = when (evalName) {
                "N1" -> 35
                "N2" -> 35
                "EXAM" -> 30
                else -> 10
            }
            
            // Determine status
            val status = when {
                gradedatesubmitted != 0L -> "submitted"
                gradedategraded != 0L -> "graded"
                graderaw.isEmpty() || graderaw == "null" -> "pending"
                else -> "graded"
            }
            
            // Parse grades
            val obtained = if (graderaw.isNotEmpty() && graderaw != "null") {
                try {
                    graderaw.toDouble()
                } catch (e: Exception) {
                    null
                }
            } else null
            
            val max = try {
                grademax.toDouble()
            } catch (e: Exception) {
                10.0
            }
            
            // Create grade group
            val gradeGroup = SgaGradeGroup(
                name = evalName,
                weight = weight,
                obtained = obtained,
                max = max,
                status = status
            }
            
            // Assign to appropriate group
            when (evalName) {
                "N1" -> n1 = gradeGroup
                "N2" -> n2 = gradeGroup
                "EXAM" -> exam = gradeGroup
            }
        }
        
        // Calculate overall grade if we have all components
        val overall = calculateOverallGrade(n1, n2, exam)
        
        return CourseGradeStructure(
            name = courseName,
            n1 = n1,
            n2 = n2,
            exam = exam,
            overallGrade = overall,
            overallMaxGrade = 100.0
        )
    }
    
    /**
     * Calculates overall grade based on weighted components
     */
    private fun calculateOverallGrade(n1: SgaGradeGroup?, n2: SgaGradeGroup?, exam: SgaGradeGroup?): Double? {
        var weightedSum = 0.0
        var totalWeight = 0
        
        if (n1?.obtained != null) {
            weightedSum += n1.obtained * n1.weight
            totalWeight += n1.weight
        }
        
        if (n2?.obtained != null) {
            weightedSum += n2.obtained * n2.weight
            totalWeight += n2.weight
        }
        
        if (exam?.obtained != null) {
            weightedSum += exam.obtained * exam.weight
            totalWeight += exam.weight
        }
        
        if (totalWeight > 0 && weightedSum > 0) {
            return weightedSum / totalWeight
        }
        
        return null
    }
}