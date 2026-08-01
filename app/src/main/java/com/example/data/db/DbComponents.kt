package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// --- 1. ENTITIES ---

@Entity(tableName = "moodle_accounts")
data class MoodleAccount(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val moodleUrl: String,
    val username: String,
    val password: String,
    val token: String?,
    val fullName: String,
    val avatarUrl: String?,
    val isActive: Boolean = false
)

@Entity(
    tableName = "courses",
    indices = [Index(value = ["accountId", "moodleCourseId"], unique = true)]
)
data class Course(
    @PrimaryKey(autoGenerate = true) val dbId: Int = 0,
    val accountId: Int,
    val moodleCourseId: Int,
    val fullName: String,
    val shortName: String
)

@Entity(
    tableName = "activity_items",
    indices = [Index(value = ["accountId", "courseId", "moodleActivityId", "moduleType"], unique = true)]
)
data class ActivityItem(
    @PrimaryKey(autoGenerate = true) val dbId: Int = 0,
    val accountId: Int,
    val courseId: Int, // Moodle's course ID
    val moodleActivityId: Int, // Moodle's activity/module ID
    val name: String,
    val moduleType: String, // "assign", "quiz", etc.
    val dueDate: Long?, // Closing epoch seconds
    val status: String, // "submitted", "not_submitted", "graded", etc.
    val grade: String? = null,
    val maxGrade: String? = null
)



@Entity(tableName = "notification_rules")
data class NotificationRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val accountId: Int,
    val title: String,
    val body: String,
    val triggerType: String, // "ON_ACTIVITY_CLOSE", "TIME_BEFORE", "CUSTOM_CODE"
    val triggerCode: String, // The code/script the AI generated to schedule/run this notification
    val timeScheduled: Long, // Epoch timestamp in millis when this notification triggers
    val isActive: Boolean = true,
    val isEnabled: Boolean = true,
    val ruleType: String = "NOTIFICATION" // "NOTIFICATION" or "ALARM"
)

@Entity(
    tableName = "triggered_alarms",
    primaryKeys = ["ruleId", "triggerKey"]
)
data class TriggeredAlarm(
    val ruleId: Int,
    val triggerKey: String,
    val timestamp: Long
)


// --- 2. DAOS ---

@Dao
interface MoodleDao {
    // Accounts
    @Query("SELECT * FROM moodle_accounts")
    fun getAllAccounts(): Flow<List<MoodleAccount>>

    @Query("SELECT * FROM moodle_accounts WHERE isActive = 1 LIMIT 1")
    fun getActiveAccountFlow(): Flow<MoodleAccount?>

    @Query("SELECT * FROM moodle_accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveAccount(): MoodleAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: MoodleAccount): Long

    @Update
    suspend fun updateAccount(account: MoodleAccount)

    @Query("UPDATE moodle_accounts SET isActive = 0")
    suspend fun deactivateAllAccounts()

    @Query("UPDATE moodle_accounts SET isActive = 1 WHERE id = :accountId")
    suspend fun activateAccount(accountId: Int)

    @Query("DELETE FROM moodle_accounts WHERE id = :accountId")
    suspend fun deleteAccountById(accountId: Int)

    // Courses
    @Query("SELECT * FROM courses WHERE accountId = :accountId")
    fun getCoursesForAccountFlow(accountId: Int): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE accountId = :accountId")
    suspend fun getCoursesForAccount(accountId: Int): List<Course>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourses(courses: List<Course>)

    @Query("DELETE FROM courses WHERE accountId = :accountId")
    suspend fun deleteCoursesForAccount(accountId: Int)

    // Activities
    @Query("SELECT * FROM activity_items WHERE accountId = :accountId")
    fun getActivitiesForAccountFlow(accountId: Int): Flow<List<ActivityItem>>

    @Query("SELECT * FROM activity_items WHERE accountId = :accountId AND courseId = :courseId")
    fun getActivitiesForCourseFlow(accountId: Int, courseId: Int): Flow<List<ActivityItem>>

    @Query("SELECT * FROM activity_items WHERE accountId = :accountId")
    suspend fun getActivitiesForAccount(accountId: Int): List<ActivityItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivities(activities: List<ActivityItem>)

    @Query("DELETE FROM activity_items WHERE accountId = :accountId")
    suspend fun deleteActivitiesForAccount(accountId: Int)

    @Query("SELECT * FROM courses")
    fun getAllCoursesFlow(): Flow<List<Course>>

    @Query("SELECT * FROM activity_items")
    fun getAllActivitiesFlow(): Flow<List<ActivityItem>>

    // Notification Rules
    @Query("SELECT * FROM notification_rules WHERE accountId = :accountId ORDER BY id DESC")
    fun getNotificationsForAccountFlow(accountId: Int): Flow<List<NotificationRule>>

    @Query("SELECT * FROM notification_rules ORDER BY id DESC")
    fun getAllNotificationsFlow(): Flow<List<NotificationRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: NotificationRule): Long

    @Query("DELETE FROM notification_rules WHERE id = :notificationId")
    suspend fun deleteNotificationById(notificationId: Int)

    // Triggered Alarms Log
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTriggeredAlarm(triggered: TriggeredAlarm)

    @Query("SELECT * FROM triggered_alarms WHERE ruleId = :ruleId")
    suspend fun getTriggeredAlarmsForRule(ruleId: Int): List<TriggeredAlarm>

    @Query("DELETE FROM triggered_alarms WHERE ruleId = :ruleId")
    suspend fun deleteTriggeredAlarmsForRule(ruleId: Int)

}

// --- 3. DATABASE ---

@Database(
    entities = [
        MoodleAccount::class,
        Course::class,
        ActivityItem::class,
        NotificationRule::class,
        TriggeredAlarm::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun moodleDao(): MoodleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "moodle_companion_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
