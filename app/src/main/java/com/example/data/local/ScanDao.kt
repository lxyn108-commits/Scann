package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanDao {
  @Query("SELECT * FROM scanned_documents ORDER BY updatedAt DESC")
  fun observeAllDocuments(): Flow<List<ScannedDocumentEntity>>

  @Query(
    """
    SELECT * FROM scanned_documents
    WHERE title LIKE '%' || :query || '%'
       OR fullOcrText LIKE '%' || :query || '%'
    ORDER BY updatedAt DESC
    """
  )
  fun searchDocuments(query: String): Flow<List<ScannedDocumentEntity>>

  @Transaction
  @Query("SELECT * FROM scanned_documents WHERE id = :documentId")
  fun observeDocumentWithPages(documentId: String): Flow<DocumentWithPages?>

  @Transaction
  @Query("SELECT * FROM scanned_documents WHERE id = :documentId")
  suspend fun getDocumentWithPages(documentId: String): DocumentWithPages?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertDocument(document: ScannedDocumentEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertPages(pages: List<ScannedPageEntity>)

  @Query("DELETE FROM scanned_pages WHERE documentId = :documentId")
  suspend fun deletePagesForDocument(documentId: String)

  @Query("UPDATE scanned_documents SET title = :title, updatedAt = :updatedAt WHERE id = :documentId")
  suspend fun updateDocumentTitle(documentId: String, title: String, updatedAt: Long)

  @Query("DELETE FROM scanned_documents WHERE id = :documentId")
  suspend fun deleteDocumentById(documentId: String)

  @Transaction
  suspend fun replaceDocumentAndPages(
    document: ScannedDocumentEntity,
    pages: List<ScannedPageEntity>,
  ) {
    insertDocument(document)
    deletePagesForDocument(document.id)
    insertPages(pages)
  }
}
