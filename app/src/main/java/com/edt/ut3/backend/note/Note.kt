package com.edt.ut3.backend.note

import androidx.room.*
import com.edt.ut3.backend.celcat.Event
import com.edt.ut3.backend.database.Converter
import java.util.*

@Entity(tableName = "note",
    foreignKeys = [
        ForeignKey(entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE
        )
    ], indices = [Index("event_id")])
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "event_id") var eventID: String?,
    var title: String?,
    var contents: String,
    @TypeConverters(Converter::class) var date: Date,
    var color: String?,
    var textColor: String?,
    var reminder: Boolean = false
)