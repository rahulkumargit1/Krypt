package com.krypt.app

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

// ─── Entities ────────────────────────────────────────────────────────────────

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uuid: String,
    val publicKey: String,
    val nickname: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,   // the other party's UUID
    val fromUuid: String,
    val content: String,          // decrypted text, or "[file:filename]" for files
    val contentType: String = "text", // "text" | "image" | "file"
    val filePath: String? = null, // local path if media
    val timestamp: Long = System.currentTimeMillis(),
    val isSent: Boolean = true
)

@Entity(tableName = "statuses")
data class StatusEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromUuid: String,
    val content: String,           // decrypted status text
    val timestamp: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
)

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface ContactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity)

    @Query("SELECT * FROM contacts ORDER BY addedAt DESC")
    fun getAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE uuid = :uuid LIMIT 1")
    suspend fun getContact(uuid: String): ContactEntity?

    @Query("DELETE FROM contacts WHERE uuid = :uuid")
    suspend fun deleteContact(uuid: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages GROUP BY conversationId HAVING MAX(timestamp) ORDER BY timestamp DESC")
    fun getConversationPreviews(): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}

@Dao
interface StatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatus(status: StatusEntity): Long

    @Query("SELECT * FROM statuses WHERE expiresAt > :now ORDER BY timestamp DESC")
    fun getActiveStatuses(now: Long = System.currentTimeMillis()): Flow<List<StatusEntity>>

    @Query("DELETE FROM statuses WHERE expiresAt <= :now")
    suspend fun deleteExpiredStatuses(now: Long = System.currentTimeMillis())

    @Query("DELETE FROM statuses WHERE fromUuid = :uuid")
    suspend fun deleteStatusByUser(uuid: String)
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [ContactEntity::class, MessageEntity::class, StatusEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KryptDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun messageDao(): MessageDao
    abstract fun statusDao(): StatusDao

    companion object {
        @Volatile private var INSTANCE: KryptDatabase? = null

        fun getInstance(context: Context): KryptDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    KryptDatabase::class.java,
                    "krypt_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
