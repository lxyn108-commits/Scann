package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "scanned_documents")
data class ScannedDocumentEntity(
  @PrimaryKey val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val pageCount: Int,
  val pdfPath: String,
  val thumbnailPath: String,
  val fullOcrText: String,
)

@Entity(
  tableName = "scanned_pages",
  foreignKeys = [
    ForeignKey(
      entity = ScannedDocumentEntity::class,
      parentColumns = ["id"],
      childColumns = ["documentId"],
      onDelete = ForeignKey.CASCADE,
    )
  ],
  indices = [Index(value = ["documentId"])],
)
data class ScannedPageEntity(
  @PrimaryKey val id: String,
  val documentId: String,
  val pageOrder: Int,
  val originalImagePath: String,
  val enhancedImagePath: String,
  val filterName: String,
  val rotationDegrees: Int,
  val ocrText: String,
  val ocrLinesJson: String,
  val imageWidth: Int,
  val imageHeight: Int,
)

data class DocumentWithPages(
  @Embedded val document: ScannedDocumentEntity,
  @Relation(
    parentColumn = "id",
    entityColumn = "documentId",
  )
  val pages: List<ScannedPageEntity>,
)
