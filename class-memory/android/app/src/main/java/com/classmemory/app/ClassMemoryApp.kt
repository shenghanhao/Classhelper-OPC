package com.classmemory.app
import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import com.classmemory.app.data.AppDatabase
import com.classmemory.app.data.MIGRATION_1_2
import com.classmemory.app.data.MIGRATION_2_3
import com.classmemory.app.data.MIGRATION_3_4
import com.classmemory.app.data.MIGRATION_4_5
import com.classmemory.app.data.MIGRATION_5_6
import com.classmemory.app.data.seedDefaultCourseTypes
import com.classmemory.app.ai.StudyResultStore
import com.classmemory.app.course.CourseContextStore
import com.classmemory.app.review.ReviewRuntime
import com.classmemory.app.review.ReviewStore
import com.classmemory.app.repository.SessionRepository
import com.classmemory.app.asr.AsrHotwordPreferences
import com.classmemory.app.asr.CustomHotwordSeed
import com.classmemory.app.i18n.AppLanguage
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
class ClassMemoryApp : Application() {
    lateinit var repository: SessionRepository
    lateinit var reviewRuntime: ReviewRuntime

    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "class-memory.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = seedDefaultCourseTypes(db)
            })
            .build()
        val reviewStore = ReviewStore(File(filesDir, "class-reviews"))
        repository = SessionRepository(
            db,
            File(filesDir, "board-photos"),
            StudyResultStore(File(filesDir, "study-results")),
            CourseContextStore(File(filesDir, "course-context.json")),
            reviewStore,
        )
        reviewRuntime = ReviewRuntime(reviewStore, repository)
        if (AsrHotwordPreferences.supportsCourseHotwords()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { CustomHotwordSeed.install(this@ClassMemoryApp) }
            }
        }
    }

}
