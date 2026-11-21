package com.desktwin.app.network

import retrofit2.Response
import retrofit2.http.*

const val DESKTWIN_PROTOCOL_VERSION = "1.0"
const val DESKTWIN_PORT = 8420

/**
 * تعريف الـ endpoints بتاعة DeskTwin Local API.
 * كل دالة هنا بتقابل endpoint موجود فعليًا وتم اختباره في api/server.py.
 */

data class StatusResponse(
    val status: String,
    val computer_name: String,
    val files_indexed: Int
)

data class CapabilitiesResponse(
    val protocol_version: String,
    val server_version: String,
    val endpoints: List<String>
)

data class FileMetadata(
    val id: Int,
    val name: String,
    val path: String,
    val extension: String?,
    val parent_folder: String?,
    val size_bytes: Long,
    val modified_at: String?,
    val created_at: String?,
    val hash: String?,
    val version: Int,
    val project: String?,
    val sync_state: String,
    val sync_mode: String
)

data class ProjectSummary(
    val project: String,
    val file_count: Int
)

data class PairingCodeResponse(
    val code: String,
    val expires_at: String,
    val valid_for_minutes: Int
)

data class VerifyCodeRequest(
    val code: String,
    val device_name: String,
    val session_minutes: Int? = null
)

data class VerifyCodeResponse(
    val success: Boolean,
    val device_token: String?,
    val error: String?,
    val certificate_fingerprint: String? = null,
    val error_code: String? = null,
    val solution: String? = null
)

data class PairingDiagnosticsResponse(
    val service: String,
    val protocol_version: String,
    val server_version: String,
    val pairing_endpoint: String,
    val tls_fingerprint: String?
)

interface DeskTwinApi {

    // ---------- Pairing (بلا حاجة لتوكن) ----------

    @POST("pair/generate-code")
    suspend fun generatePairingCode(): Response<PairingCodeResponse>

    @POST("pair/verify-code")
    suspend fun verifyPairingCode(@Body request: VerifyCodeRequest): Response<VerifyCodeResponse>

    @GET("pair/diagnostics")
    suspend fun pairingDiagnostics(): Response<PairingDiagnosticsResponse>

    // ---------- Status ----------

    @GET("status")
    suspend fun getStatus(@Header("X-Device-Token") token: String): Response<StatusResponse>

    @GET("capabilities")
    suspend fun getCapabilities(@Header("X-Device-Token") token: String): Response<CapabilitiesResponse>

    // ---------- Files (Metadata فقط - القاعدة الذهبية) ----------

    @GET("files")
    suspend fun listFiles(
        @Header("X-Device-Token") token: String,
        @Query("query") query: String = "",
        @Query("project") project: String? = null,
        @Query("sync_state") syncState: String? = null,
        @Query("limit") limit: Int = 100
    ): Response<List<FileMetadata>>

    @GET("files/{id}")
    suspend fun getFileMetadata(
        @Header("X-Device-Token") token: String,
        @Path("id") fileId: Int
    ): Response<FileMetadata>

    @GET("projects")
    suspend fun listProjects(@Header("X-Device-Token") token: String): Response<List<ProjectSummary>>

    // ---------- Content (طلب صريح فقط - الاستثناء الواعي) ----------

    @Streaming
    @GET("files/{id}/content")
    suspend fun getFileContent(
        @Header("X-Device-Token") token: String,
        @Path("id") fileId: Int,
        @Tag progressListener: ProgressListener? = null
    ): Response<okhttp3.ResponseBody>

    /**
     * بديل لـ getFileContent لما DirEntry.file_id يكون null - حالة ملف
     * ظهر لتوّه في /browse لكن لسه ماتفهرسش بالكامل في السيرفر. السيرفر
     * بيفهرسه فورًا وقت الطلب ده نفسه (upsert فوري) بدل ما يرفض بـ 404.
     */
    @Streaming
    @GET("files/content_by_path")
    suspend fun getFileContentByPath(
        @Header("X-Device-Token") token: String,
        @Query("path") path: String,
        @Tag progressListener: ProgressListener? = null
    ): Response<okhttp3.ResponseBody>

    @GET("files/{id}/thumbnail")
    suspend fun getFileThumbnail(
        @Header("X-Device-Token") token: String,
        @Path("id") fileId: Int,
        @Query("path") path: String? = null
    ): Response<okhttp3.ResponseBody>

    @POST("files/{id}/availability")
    suspend fun setFileAvailability(
        @Header("X-Device-Token") token: String,
        @Path("id") fileId: Int,
        @Body request: FileAvailabilityRequest
    ): Response<FileAvailabilityResponse>

    @POST("files/{id}/show_in_explorer")
    suspend fun showInExplorer(
        @Header("X-Device-Token") token: String,
        @Path("id") fileId: Int,
        @Query("path") path: String? = null
    ): Response<CommandExecutionResponse>

    @POST("files/{fileId}/open")
    suspend fun openFileOnComputer(
        @Header("X-Device-Token") token: String,
        @Path("fileId") fileId: Int,
        @Query("path") path: String? = null
    ): Response<CommandExecutionResponse>

    @POST("files/{fileId}/set-wallpaper")
    suspend fun setWallpaper(
        @Header("X-Device-Token") token: String,
        @Path("fileId") fileId: Int
    ): Response<CommandExecutionResponse>

    @GET("files/{fileId}/versions")
    suspend fun listFileVersions(
        @Header("X-Device-Token") token: String,
        @Path("fileId") fileId: Int
    ): Response<List<FileVersionEntry>>

    @POST("files/{fileId}/versions/savepoint")
    suspend fun createFileSavepoint(
        @Header("X-Device-Token") token: String,
        @Path("fileId") fileId: Int
    ): Response<FileVersionEntry>

    @POST("files/{fileId}/versions/{versionId}/restore")
    suspend fun restoreFileVersion(
        @Header("X-Device-Token") token: String,
        @Path("fileId") fileId: Int,
        @Path("versionId") versionId: Int
    ): Response<okhttp3.ResponseBody>

    @POST("files/operate")
    suspend fun operateFile(
        @Header("X-Device-Token") token: String,
        @Body request: FileOperationRequest
    ): Response<FileOperationResponse>

    @POST("files/operate-batch")
    suspend fun operateFileBatch(
        @Header("X-Device-Token") token: String,
        @Body request: FileOperationBatchRequest
    ): Response<FileOperationBatchResponse>

    @POST("device/action")
    suspend fun deviceAction(
        @Header("X-Device-Token") token: String,
        @Body request: DeviceActionRequest
    ): Response<DeviceActionResponse>

    @POST("trash/restore")
    suspend fun restoreFromTrash(
        @Header("X-Device-Token") token: String,
        @Body request: RestoreRequest
    ): Response<okhttp3.ResponseBody>

    @POST("trash/delete-permanently")
    suspend fun permanentlyDeleteFromTrash(
        @Header("X-Device-Token") token: String,
        @Body request: RestoreRequest
    ): Response<okhttp3.ResponseBody>

    @GET("trash")
    suspend fun listTrash(@Header("X-Device-Token") token: String): Response<List<TrashItem>>

    @GET("security/status")
    suspend fun securityStatus(
        @Header("X-Device-Token") token: String
    ): Response<SecurityStatusResponse>

    @GET("integration/health")
    suspend fun integrationHealth(@Header("X-Device-Token") token: String): Response<IntegrationHealthResponse>

    @GET("activity")
    suspend fun getActivity(
        @Header("X-Device-Token") token: String,
        @Query("limit") limit: Int = 50
    ): Response<List<ActivityEntry>>

    @GET("conflicts")
    suspend fun listConflicts(
        @Header("X-Device-Token") token: String,
        @Query("status") status: String = "pending"
    ): Response<List<ConflictEntry>>

    @POST("conflicts/{conflictId}/resolve")
    suspend fun resolveConflict(
        @Header("X-Device-Token") token: String,
        @Path("conflictId") conflictId: Int,
        @Body request: ResolveConflictRequest
    ): Response<ConflictResolveResponse>

    // ---------- Upload (رفع ملف من الموبايل للكمبيوتر) ----------

    @POST("uploads/prepare")
    suspend fun prepareUpload(
        @Header("X-Device-Token") token: String,
        @Body request: UploadPreparationRequest
    ): Response<UploadPreparationResponse>

    @Multipart
    @POST("upload")
    suspend fun uploadFile(
        @Header("X-Device-Token") token: String,
        @Query("target_folder") targetFolder: String,
        @Query("collision_mode") collisionMode: String = "duplicate",
        @Query("approval_id") approvalId: Int? = null,
        @Part file: okhttp3.MultipartBody.Part
    ): Response<UploadResponse>

    // ---------- Resumable Upload (ملفات كبيرة ≥1GB - استكمال بعد انقطاع) ----------

    @POST("uploads/resumable/negotiate")
    suspend fun negotiateResumableUpload(
        @Header("X-Device-Token") token: String,
        @Body request: NegotiateResumableRequest
    ): Response<NegotiateResumableResponse>

    @Multipart
    @POST("uploads/resumable/chunk")
    suspend fun uploadResumableChunk(
        @Header("X-Device-Token") token: String,
        @Query("destination") destination: String,
        @Query("offset") offset: Long,
        @Part file: okhttp3.MultipartBody.Part
    ): Response<okhttp3.ResponseBody>

    @POST("uploads/resumable/complete")
    suspend fun completeResumableUpload(
        @Header("X-Device-Token") token: String,
        @Query("destination") destination: String
    ): Response<UploadResponse>

    @GET("uploads/resumable/incomplete")
    suspend fun listIncompleteResumableTransfers(
        @Header("X-Device-Token") token: String
    ): Response<List<IncompleteTransfer>>

    // ---------- Offline Tree Mirror (نسخة شجرة الملفات - metadata بس) ----------

    @GET("sync/full-tree-snapshot")
    suspend fun getFullTreeSnapshot(
        @Header("X-Device-Token") token: String
    ): Response<List<TreeEntry>>

    @POST("sync/tree-diff")
    suspend fun computeTreeDiff(
        @Header("X-Device-Token") token: String,
        @Body request: TreeDiffRequest
    ): Response<TreeDiffResponse>

    // ---------- Offline Deferred Command Batch (طابور الأوامر المؤجلة) ----------

    @POST("sync/batch/submit")
    suspend fun submitOfflineBatch(
        @Header("X-Device-Token") token: String,
        @Body request: SubmitBatchRequest
    ): Response<BatchExecutionResult>

    @POST("sync/batch/{batchId}/resume")
    suspend fun resumeOfflineBatch(
        @Header("X-Device-Token") token: String,
        @Path("batchId") batchId: Int
    ): Response<BatchExecutionResult>

    @POST("sync/batch/{batchId}/skip-current")
    suspend fun skipCurrentConflictedCommand(
        @Header("X-Device-Token") token: String,
        @Path("batchId") batchId: Int
    ): Response<okhttp3.ResponseBody>

    @POST("sync/batch/{batchId}/cancel")
    suspend fun cancelOfflineBatch(
        @Header("X-Device-Token") token: String,
        @Path("batchId") batchId: Int
    ): Response<okhttp3.ResponseBody>

    @GET("sync/batch/{batchId}")
    suspend fun getBatchStatus(
        @Header("X-Device-Token") token: String,
        @Path("batchId") batchId: Int
    ): Response<BatchExecutionResult>

    // ---------- Browse (تصفح شجرة الفولدرات الحقيقية) ----------

    @GET("browse")
    suspend fun browsePath(
        @Header("X-Device-Token") token: String,
        @Query("path") path: String
    ): Response<BrowseResponse>

    @GET("home")
    suspend fun getHome(@Header("X-Device-Token") token: String): Response<HomeResponse>

    // ---------- Approvals (طلبات محتاجة موافقة الكمبيوتر) ----------

    @POST("approvals/request")
    suspend fun requestApproval(
        @Header("X-Device-Token") token: String,
        @Body request: ApprovalRequest
    ): Response<ApprovalResponse>

    @GET("approvals/{id}")
    suspend fun getApprovalStatus(
        @Header("X-Device-Token") token: String,
        @Path("id") approvalId: Int
    ): Response<ApprovalResponse>

    // ---------- Scope Expansion (طلب توسيع نطاق - محتاج موافقة، مش فوري) ----------

    @GET("targets")
    suspend fun listTargets(@Header("X-Device-Token") token: String): Response<List<TargetInfo>>

    @POST("targets/request")
    suspend fun requestNewTarget(
        @Header("X-Device-Token") token: String,
        @Body request: TargetRequest
    ): Response<TargetRequestResponse>

    // ---------- User Binding ----------

    @GET("user")
    suspend fun getBoundUser(@Header("X-Device-Token") token: String): Response<BoundUserResponse>

    @POST("user/bind")
    suspend fun bindUser(
        @Header("X-Device-Token") token: String,
        @Body request: BindUserRequest
    ): Response<BoundUserResponse>
}

data class ApprovalRequest(
    val action_type: String,
    val file_id: Int? = null,
    val file_path: String? = null,
    val new_value: String? = null
)

data class ActivityEntry(
    val id: Int,
    val activity_type: String,
    val detail: String? = null,
    val created_at: String? = null
)

data class FileVersionEntry(
    val id: Int,
    val file_path: String,
    val version_number: Int = 0,
    val stored_copy_path: String = "",
    val hash: String? = null,
    val size_bytes: Long = 0,
    val source_device: String = "",
    val reason: String = "",
    val created_at: String = ""
)

data class ConflictEntry(
    val id: Int,
    val file_path: String,
    val pc_hash: String? = null,
    val pc_modified_at: String? = null,
    val phone_hash: String? = null,
    val phone_modified_at: String? = null,
    val detected_at: String,
    val status: String,
    val resolved_at: String? = null
)

data class ResolveConflictRequest(val resolution: String)
data class ConflictResolveResponse(val success: Boolean, val conflict_id: Int, val status: String)

data class ApprovalResponse(
    val approval_id: Int,
    val status: String
)

data class BindUserRequest(val user_name: String)

data class BoundUserResponse(
    val user_name: String?,
    val bound_at: String?
)

data class UploadResponse(
    val success: Boolean,
    val file_id: Int,
    val saved_path: String,
    val size_bytes: Long
)

data class DirEntry(
    val name: String,
    val path: String,
    val is_folder: Boolean,
    val size_bytes: Long,
    val modified_at: String?,
    val extension: String?,
    val file_id: Int? = null  // متاح فقط للملفات المفهرسة والمشاركة - يفعّل أزرار التحميل مباشرة
)

data class BrowseResponse(
    val current_path: String,
    val parent_path: String?,
    val entries: List<DirEntry>
)

data class SpecialFolderEntry(val name: String, val path: String)
data class DriveEntry(val name: String, val path: String)

data class HomeResponse(
    val home_path: String,
    val special_folders: List<SpecialFolderEntry>,
    val drives: List<DriveEntry>
)

data class UploadPreparationRequest(
    val target_folder: String,
    val file_name: String,
    val collision_mode: String = "duplicate"  // duplicate | replace
)

data class UploadPreparationResponse(
    val status: String,  // ready | pending_approval
    val approval_id: Int? = null,
    val destination: String
)

// ---------- Resumable Upload models ----------

data class NegotiateResumableRequest(
    val target_folder: String,
    val file_name: String,
    val total_size_bytes: Long,
    val source_fingerprint: String
)

data class NegotiateResumableResponse(
    val destination: String,
    val resume_offset: Long,
    val fingerprint_matched: Boolean
)

data class IncompleteTransfer(
    val id: Int,
    val file_path: String,
    val direction: String,
    val total_size_bytes: Long,
    val bytes_received: Long,
    val source_fingerprint: String,
    val device_token: String,
    val status: String,
    val created_at: String,
    val last_progress_at: String,
    val retention_hours: Int
)

// ---------- Offline Tree Mirror models ----------

data class TreeEntry(
    val path: String,
    val name: String,
    val is_folder: Boolean,
    val size_bytes: Long,
    val modified_at: String? = null,
    val extension: String? = null,
    val file_id: Int? = null
)

data class TreeSnapshotEntry(
    val path: String,
    val is_folder: Boolean,
    val size_bytes: Long,
    val modified_at: String
)

data class TreeDiffRequest(
    val snapshot: List<TreeSnapshotEntry>
)

data class TreeDiffResponse(
    val added: List<TreeEntry>,
    val removed: List<String>,
    val modified: List<TreeEntry>
)

// ---------- Offline Deferred Command Batch models ----------

data class OfflineCommandItem(
    val command_type: String,  // delete | rename | download | upload
    val file_path: String,
    val new_value: String? = null
)

data class SubmitBatchRequest(
    val commands: List<OfflineCommandItem>
)

data class BatchCommandResult(
    val id: Int,
    val sequence_index: Int,
    val command_type: String,
    val file_path: String,
    val new_value: String? = null,
    val status: String,  // pending | executed | conflict | skipped | cancelled
    val conflict_reason: String? = null
)

data class BatchExecutionResult(
    val batch_id: Int,
    val status: String,  // completed | stopped_on_conflict
    val commands: List<BatchCommandResult>
)

data class FileAvailabilityRequest(
    val mode: String  // temporary | shared
)

data class FileAvailabilityResponse(
    val success: Boolean,
    val file_id: Int,
    val sync_mode: String,
    val approval_id: Int? = null
)

data class FileOperationRequest(
    val operation: String,
    val source: String,
    val destination: String? = null,
    val new_name: String? = null,
    val collision_mode: String = "reject"
)

data class FileOperationResponse(
    val success: Boolean,
    val operation: String,
    val path: String,
    val destination: String? = null,
    val error: String? = null
)

data class FileOperationBatchRequest(val operations: List<FileOperationRequest>)

data class FileOperationBatchResponse(
    val results: List<FileOperationResponse>,
    val succeeded: Int,
    val failed: Int
)

data class DeviceActionRequest(val action: String)
data class DeviceActionResponse(val success: Boolean, val action: String, val message: String)
data class CommandExecutionResponse(
    val success: Boolean = false,
    val status: String = "",
    val operation: String = "",
    val path: String? = null
)
data class RestoreRequest(val original_path: String)
data class TrashItem(val item_id: String, val original_path: String, val deleted_at: Double)

data class SecurityStatusResponse(
    val status: String,
    val blocked_sources: Int,
    val recent_failed_attempts: Int,
    val window_seconds: Int
)

data class IntegrationHealthResponse(
    val status: String,
    val protocol_version: String,
    val server_version: String,
    val trust_level: String,
    val websocket: String,
    val chat: String,
    val endpoints: List<String>,
    val hints: List<String>
)

data class TargetInfo(
    val path: String,
    val label: String,
    val recursive: Boolean,
    val enabled: Boolean,
    val is_file: Boolean
)

data class TargetRequest(
    val path: String,
    val label: String,
    val recursive: Boolean = true
)

data class TargetRequestResponse(
    val success: Boolean,
    val approval_id: Int?,
    val status: String
)
