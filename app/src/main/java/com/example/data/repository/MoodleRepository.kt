package com.example.data.repository

import android.util.Log
import com.example.data.db.*
import com.example.data.network.MoodleApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

class MoodleRepository(private val moodleDao: MoodleDao) {
    private val TAG = "MoodleRepository"

    // Flows
    val allAccounts: Flow<List<MoodleAccount>> = moodleDao.getAllAccounts()
    val activeAccount: Flow<MoodleAccount?> = moodleDao.getActiveAccountFlow()

    fun getCoursesFlow(accountId: Int): Flow<List<Course>> = moodleDao.getCoursesForAccountFlow(accountId)
    fun getActivitiesFlow(accountId: Int): Flow<List<ActivityItem>> = moodleDao.getActivitiesForAccountFlow(accountId)
    fun getActivitiesForCourseFlow(accountId: Int, courseId: Int): Flow<List<ActivityItem>> = 
        moodleDao.getActivitiesForCourseFlow(accountId, courseId)
    val allCourses: Flow<List<Course>> = moodleDao.getAllCoursesFlow()
    val allActivities: Flow<List<ActivityItem>> = moodleDao.getAllActivitiesFlow()
    val allNotifications: Flow<List<NotificationRule>> = moodleDao.getAllNotificationsFlow()
    fun getNotificationsFlow(accountId: Int): Flow<List<NotificationRule>> = moodleDao.getNotificationsForAccountFlow(accountId)

    /**
     * Authenticates and registers a new Moodle account.
     */
    suspend fun addNewAccount(url: String, username: String, password: String): MoodleAccount = withContext(Dispatchers.IO) {
        moodleDao.deactivateAllAccounts()

        // Handle Demo Account Login
        if (url.trim().lowercase() == "demo" || username.trim().lowercase() == "demo") {
            val demoAccount = MoodleApiClient.getDemoAccount()
            moodleDao.insertAccount(demoAccount)
            // Seed initial demo data
            moodleDao.deleteCoursesForAccount(demoAccount.id)
            moodleDao.deleteActivitiesForAccount(demoAccount.id)
            moodleDao.insertCourses(MoodleApiClient.getDemoCourses(demoAccount.id))
            moodleDao.insertActivities(MoodleApiClient.getDemoActivities(demoAccount.id))
            
            // Seed a starter alarm
            val now = System.currentTimeMillis()
            moodleDao.insertNotification(NotificationRule(
                accountId = demoAccount.id,
                title = "Quiz 1 Prep Alert",
                body = "Your quiz closes soon! Revise backpropagation.",
                triggerType = "TIME_BEFORE",
                triggerCode = "if (now() >= course(101).activity(1001).dueDate - hours(2)) { trigger() }",
                timeScheduled = now + 60 * 1000L // 1 minute from now
            ))
            
            return@withContext demoAccount
        }

        // Real Moodle API token request
        val token = MoodleApiClient.fetchToken(url, username, password)
        // Site info
        val siteInfo = MoodleApiClient.fetchSiteInfo(url, token)

        val account = MoodleAccount(
            moodleUrl = url,
            username = username,
            password = password,
            token = token,
            fullName = siteInfo.fullname,
            avatarUrl = siteInfo.avatarUrl,
            isActive = true
        )

        val rowId = moodleDao.insertAccount(account)
        val createdAccount = account.copy(id = rowId.toInt())

        // Initial sync
        try {
            syncData(createdAccount)
        } catch (e: Exception) {
            Log.e(TAG, "Initial sync failed", e)
        }

        return@withContext createdAccount
    }

    /**
     * Performs a full sync of Moodle courses, activities, and grades.
     */
    suspend fun syncActiveAccount() = withContext(Dispatchers.IO) {
        val active = moodleDao.getActiveAccount() ?: return@withContext
        syncData(active)
    }

    /**
     * Performs a sync of all connected Moodle accounts.
     */
    suspend fun syncAllAccounts(accounts: List<MoodleAccount>) = withContext(Dispatchers.IO) {
        accounts.forEach { account ->
            try {
                syncData(account)
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed for account ${account.fullName}", e)
            }
        }
    }

    private suspend fun syncData(account: MoodleAccount) {
        if (account.id == 9999) {
            // Simulated sync for demo mode
            moodleDao.insertCourses(MoodleApiClient.getDemoCourses(account.id))
            moodleDao.insertActivities(MoodleApiClient.getDemoActivities(account.id))
            return
        }

        val token = account.token ?: return
        val url = account.moodleUrl

        // 1. Fetch site info again to get user ID
        val siteInfo = MoodleApiClient.fetchSiteInfo(url, token)
        
        // 2. Fetch Courses
        val courses = MoodleApiClient.fetchCourses(url, token, siteInfo.userId, account.id)
        if (courses.isNotEmpty()) {
            moodleDao.deleteCoursesForAccount(account.id)
            moodleDao.insertCourses(courses)

            // 3. Fetch activities & grades for each course
            val allActivities = mutableListOf<ActivityItem>()
            courses.forEach { course ->
                try {
                    val acts = MoodleApiClient.fetchCourseActivities(url, token, course.moodleCourseId, account.id, siteInfo.userId)
                    allActivities.addAll(acts)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing course ${course.fullName}", e)
                }
            }

            if (allActivities.isNotEmpty()) {
                moodleDao.deleteActivitiesForAccount(account.id)
                moodleDao.insertActivities(allActivities)
            }
        }
    }

    suspend fun switchAccount(accountId: Int) = withContext(Dispatchers.IO) {
        moodleDao.deactivateAllAccounts()
        moodleDao.activateAccount(accountId)
    }

    suspend fun deleteAccount(accountId: Int) = withContext(Dispatchers.IO) {
        moodleDao.deleteAccountById(accountId)
        moodleDao.deleteCoursesForAccount(accountId)
        moodleDao.deleteActivitiesForAccount(accountId)
    }



    // Notifications
    suspend fun createNotification(rule: NotificationRule): Long = withContext(Dispatchers.IO) {
        moodleDao.insertNotification(rule)
    }

    suspend fun deleteNotification(ruleId: Int) = withContext(Dispatchers.IO) {
        moodleDao.deleteNotificationById(ruleId)
    }
}
