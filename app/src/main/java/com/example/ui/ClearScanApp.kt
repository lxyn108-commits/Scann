package com.example.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.BatchReviewScreen
import com.example.ui.screens.CameraBatchScreen
import com.example.ui.screens.DocumentViewerScreen
import com.example.ui.screens.LibraryScreen

object ClearScanRoutes {
  const val ROUTE_LIBRARY = "library"
  const val ROUTE_CAMERA = "camera"
  const val ROUTE_BATCH_REVIEW = "batch_review"
  const val ROUTE_VIEWER = "viewer/{documentId}"

  fun viewerRoute(documentId: String): String = "viewer/$documentId"
}

@Composable
fun ClearScanApp(
  mainViewModel: MainViewModel = viewModel(),
) {
  val navController = rememberNavController()
  val snackbarHostState = remember { SnackbarHostState() }

  val documents by mainViewModel.documents.collectAsStateWithLifecycle()
  val searchQuery by mainViewModel.searchQuery.collectAsStateWithLifecycle()
  val batchState by mainViewModel.batchState.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    mainViewModel.snackbarMessages.collect { message ->
      snackbarHostState.showSnackbar(message)
    }
  }

  NavHost(
    navController = navController,
    startDestination = ClearScanRoutes.ROUTE_LIBRARY,
  ) {
    composable(ClearScanRoutes.ROUTE_LIBRARY) {
      LibraryScreen(
        documents = documents,
        searchQuery = searchQuery,
        isImporting = batchState.isImportingOrCapturing,
        snackbarHostState = snackbarHostState,
        onSearchQueryChange = mainViewModel::updateSearchQuery,
        onStartBatchScan = {
          mainViewModel.startNewBatchSession()
          navController.navigate(ClearScanRoutes.ROUTE_CAMERA)
        },
        onImportPhotos = { uris ->
          mainViewModel.startNewBatchSession()
          mainViewModel.onImportPhotoUris(uris) {
            navController.navigate(ClearScanRoutes.ROUTE_BATCH_REVIEW)
          }
        },
        onOpenDocument = { docId ->
          navController.navigate(ClearScanRoutes.viewerRoute(docId))
        },
        onSharePdf = mainViewModel::shareSearchablePdf,
        onDeleteDocument = { docId -> mainViewModel.deleteDocument(docId) },
      )
    }

    composable(ClearScanRoutes.ROUTE_CAMERA) {
      CameraBatchScreen(
        batchPages = batchState.pages,
        activePreset = batchState.activePreset,
        isProcessingCapture = batchState.isImportingOrCapturing,
        isRetakingPage = batchState.retakingPageIndex != null,
        onSelectPreset = mainViewModel::selectScanPreset,
        onPhotoCaptured = { file, quad, openStudioAfter ->
          mainViewModel.onPhotoCaptured(
            tempFile = file,
            hintedQuad = quad,
            onCompleted = if (openStudioAfter) {
              {
                navController.navigate(ClearScanRoutes.ROUTE_BATCH_REVIEW) {
                  launchSingleTop = true
                }
              }
            } else {
              null
            },
          )
        },
        onImportPhotos = { uris ->
          mainViewModel.onImportPhotoUris(uris) {
            navController.navigate(ClearScanRoutes.ROUTE_BATCH_REVIEW) {
              launchSingleTop = true
            }
          }
        },
        onRemovePage = mainViewModel::removeBatchPage,
        onReviewBatch = {
          navController.navigate(ClearScanRoutes.ROUTE_BATCH_REVIEW) {
            launchSingleTop = true
          }
        },
        onClose = {
          mainViewModel.clearRetakePage()
          navController.popBackStack()
        },
      )
    }

    composable(ClearScanRoutes.ROUTE_BATCH_REVIEW) {
      BatchReviewScreen(
        state = batchState,
        onTitleChange = mainViewModel::updateDraftTitle,
        onSelectPage = mainViewModel::selectBatchPage,
        onMovePage = mainViewModel::moveBatchPage,
        onRemovePage = mainViewModel::removeBatchPage,
        onRetakePage = { index ->
          mainViewModel.prepareRetakePage(index)
          navController.navigate(ClearScanRoutes.ROUTE_CAMERA) {
            launchSingleTop = true
          }
        },
        onApplyFilter = mainViewModel::applyFilterToSelectedPage,
        onApplyFilterToAll = mainViewModel::applyFilterToAllPages,
        onApplySuggestion = mainViewModel::applySuggestion,
        onApplyAllSuggestions = mainViewModel::applyAllSuggestionsForSelectedPage,
        onRotatePage = mainViewModel::rotateSelectedPage,
        onApplyPerspectiveQuad = mainViewModel::applyPerspectiveToSelectedPage,
        onToggleShadowRemoval = mainViewModel::toggleShadowRemovalForSelectedPage,
        onCopyText = mainViewModel::copyOcrText,
        onAddMorePages = {
          mainViewModel.clearRetakePage()
          navController.navigate(ClearScanRoutes.ROUTE_CAMERA) {
            launchSingleTop = true
          }
        },
        onSaveSearchablePdf = {
          mainViewModel.saveBatchAsSearchablePdf { savedDocId ->
            navController.navigate(ClearScanRoutes.viewerRoute(savedDocId)) {
              popUpTo(ClearScanRoutes.ROUTE_LIBRARY) { inclusive = false }
            }
          }
        },
        onBack = { navController.popBackStack() },
      )
    }

    composable(
      route = ClearScanRoutes.ROUTE_VIEWER,
      arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
    ) { backStackEntry ->
      val docId = backStackEntry.arguments?.getString("documentId").orEmpty()
      val docWithPages by remember(docId) {
        mainViewModel.observeDocument(docId)
      }.collectAsStateWithLifecycle(initialValue = null)

      DocumentViewerScreen(
        documentWithPages = docWithPages,
        snackbarHostState = snackbarHostState,
        onBack = { navController.popBackStack() },
        onEditDocument = { id ->
          mainViewModel.openDocumentForEditing(id) {
            navController.navigate(ClearScanRoutes.ROUTE_BATCH_REVIEW)
          }
        },
        onDeleteDocument = { id ->
          mainViewModel.deleteDocument(id) {
            navController.popBackStack()
          }
        },
        onSaveSearchablePdfUri = mainViewModel::exportPdfToUri,
        onSaveTextPdfUri = mainViewModel::exportTextOnlyPdfToUri,
        onSharePdf = mainViewModel::shareSearchablePdf,
        onExportImagesToGallery = mainViewModel::exportImagesToGallery,
        onShareImages = mainViewModel::sharePageImages,
        onCopyText = mainViewModel::copyOcrText,
      )
    }
  }
}
