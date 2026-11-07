package lk.expensetracker.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import lk.expensetracker.engine.model.Channel
import lk.expensetracker.engine.model.Direction
import lk.expensetracker.engine.model.Kind
import lk.expensetracker.engine.model.LinkType
import lk.expensetracker.engine.model.TimeSource

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    /** Returns -1 when the SMS was already imported. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TransactionEntity): Long

    @Update
    suspend fun update(entity: TransactionEntity)

    @Update
    suspend fun updateAll(entities: List<TransactionEntity>)

    @Query("UPDATE transactions SET category = :category WHERE merchantKey = :merchantKey AND userEditedCategory = 0")
    suspend fun recategorizeMerchant(merchantKey: String, category: String)

    @Query("SELECT COUNT(*) FROM transactions WHERE needsReview = 1 OR linkType = 'SUGGESTED_INTERNAL_PAIR'")
    fun observeReviewCount(): Flow<Int>

    @Query("SELECT * FROM transactions")
    suspend fun exportAll(): List<TransactionEntity>
}

@Dao
interface UnparsedDao {
    @Query("SELECT * FROM unparsed_messages WHERE status = 'NEW' ORDER BY receivedAt DESC")
    fun observeNew(): Flow<List<UnparsedMessageEntity>>

    @Query("SELECT * FROM unparsed_messages WHERE status = 'NEW'")
    suspend fun getNew(): List<UnparsedMessageEntity>

    @Query("SELECT * FROM unparsed_messages WHERE id = :id")
    suspend fun get(id: Long): UnparsedMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: UnparsedMessageEntity): Long

    @Query("UPDATE unparsed_messages SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: UnparsedStatus)

    @Query("UPDATE unparsed_messages SET status = 'RESOLVED' WHERE smsHash = :hash")
    suspend fun resolveByHash(hash: String)
}

@Dao
interface BankPackDao {
    @Query("SELECT * FROM bank_packs ORDER BY builtIn DESC, id")
    fun observeAll(): Flow<List<BankPackEntity>>

    @Query("SELECT * FROM bank_packs")
    suspend fun getAll(): List<BankPackEntity>

    @Query("SELECT * FROM bank_packs WHERE id = :id")
    suspend fun get(id: String): BankPackEntity?

    @Upsert
    suspend fun upsert(entity: BankPackEntity)

    @Query("DELETE FROM bank_packs WHERE id = :id AND builtIn = 0")
    suspend fun deleteCustom(id: String)
}

@Dao
interface MerchantCategoryDao {
    @Query("SELECT * FROM merchant_categories")
    suspend fun getAll(): List<MerchantCategoryEntity>

    @Upsert
    suspend fun upsert(entity: MerchantCategoryEntity)
}

class Converters {
    @TypeConverter fun fromDirection(v: Direction) = v.name
    @TypeConverter fun toDirection(v: String) = Direction.valueOf(v)
    @TypeConverter fun fromKind(v: Kind) = v.name
    @TypeConverter fun toKind(v: String) = Kind.valueOf(v)
    @TypeConverter fun fromChannel(v: Channel) = v.name
    @TypeConverter fun toChannel(v: String) = Channel.valueOf(v)
    @TypeConverter fun fromTimeSource(v: TimeSource) = v.name
    @TypeConverter fun toTimeSource(v: String) = TimeSource.valueOf(v)
    @TypeConverter fun fromLinkType(v: LinkType?) = v?.name
    @TypeConverter fun toLinkType(v: String?) = LinkType.parse(v)
    @TypeConverter fun fromStatus(v: UnparsedStatus) = v.name
    @TypeConverter fun toStatus(v: String) = UnparsedStatus.valueOf(v)
}

@Database(
    entities = [TransactionEntity::class, UnparsedMessageEntity::class, BankPackEntity::class, MerchantCategoryEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun unparsed(): UnparsedDao
    abstract fun bankPacks(): BankPackDao
    abstract fun merchantCategories(): MerchantCategoryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "expenses.db").build()
    }
}
