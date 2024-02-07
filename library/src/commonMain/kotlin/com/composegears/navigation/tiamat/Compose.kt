package com.composegears.navigation.tiamat

import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

private const val KEY_NAV_ARGS = "args"
private const val KEY_NAV_RESULT = "result"

internal val LocalNavController = staticCompositionLocalOf<NavController?> { null }
internal val LocalDataStore = staticCompositionLocalOf<DataStorage?> { null }

enum class StorageMode {
    /**
     * Savable storage, persist internal cleanups
     */
    Savable,

    /**
     * In memory data storage, navController will reset on data loss
     */
    DataStore
}

internal data class DataStorage(val data: HashMap<String, Any?> = hashMapOf())

private fun NavEntry.storageKey() = "EntryStorage#$uuid"

/**
 * Create and provide [NavController] instance to be used in [Navigation]
 *
 * @param key key to be bind to created NavController
 * @param storageMode data storage mode [StorageMode.Savable] or [StorageMode.DataStore]
 * @param startDestination destination to be used as initial
 * @param destinations array of allowed destinations for this controller
 */
@Composable
@Suppress("ComposableParamOrder")
fun rememberNavController(
    key: Any? = null,
    storageMode: StorageMode = StorageMode.DataStore,
    startDestination: NavDestination<*>? = null,
    destinations: Array<NavDestination<*>>
): NavController {
    val parent = LocalNavController.current
    val parentDataStorage = LocalDataStore.current
    val navDataStore =
        if (parentDataStorage == null) rootDataStore()
        else {
            val storageKey = "DataStore#" + currentCompositeKeyHash.toString(16)
            parentDataStorage.data.getOrPut(storageKey) { DataStorage() } as DataStorage
        }

    fun createNavController(state: Map<String, Any?>?) =
        NavController(
            parent = parent,
            key = key,
            storageMode = storageMode,
            startDestination = startDestination,
            savedState = state,
            dataStorage = navDataStore,
            destinations = destinations
        ).apply {
            if (storageMode == StorageMode.DataStore && navDataStore.data.isEmpty()) reset()
            else restoreFromSavedState()
        }
    return rememberSaveable(
        saver = Saver(
            { navController -> navController.toSavedState() },
            { savedState: Map<String, Any?> -> createNavController(savedState) }
        ),
        init = { createNavController(null) }
    )
}

@Composable
private fun <Args> DestinationContent(destination: NavDestination<Args>) {
    val scope = remember(destination) { NavDestinationScopeImpl<Args>() }
    with(destination) { scope.Content() }
}

@Composable
private fun BoxScope.Overlay() {
    Box(Modifier
        .matchParentSize()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach {
                        it.consume()
                    }
                }
            }
        }
    )
}


/**
 * Created a content of [NavController] (see [rememberNavController])
 *
 * Root element of the content is [AnimatedContent]
 *
 * @param navController displayed navController
 * @param modifier modifier to be passed to root [AnimatedContent]
 * @param handleSystemBackEvent allow to call [NavController.back] on system `back` event if possible
 * @param contentTransformProvider default nav transition provided to be used
 * if no transition provided by navigation functions
 *
 * @see [NavController.navigate]
 * @see [NavController.replace]]
 * @see [NavController.back]
 * @see [navController]
 * @see [navArgs]
 * @see [navResult]
 * @see [rememberViewModel]
 */
@Composable
@OptIn(ExperimentalAnimationApi::class)
fun Navigation(
    navController: NavController,
    modifier: Modifier = Modifier,
    handleSystemBackEvent: Boolean = true,
    contentTransformProvider: (isForward: Boolean) -> ContentTransform = { navigationFadeInOut() }
) {
    if (handleSystemBackEvent) {
        val canGoBack by remember(navController.current) { derivedStateOf { navController.canGoBack() } }
        PlatformBackHandler(canGoBack, navController::back)
    }
    // listen to entries being closed/removed from backstack and clear storage/models recursively
    DisposableEffect(navController) {
        val entryCloseDispatcher: (NavEntry) -> Unit = {
            navController.dataStorage.data.remove(it.storageKey())
            val detachList = mutableListOf(it.entryStorage)
            while (detachList.isNotEmpty()) {
                detachList.removeAt(0)?.data?.onEach { (_, value) ->
                    when (value) {
                        is DataStorage -> detachList.add(value)
                        is ComposeViewModel -> value.close()
                    }
                }
            }
        }
        navController.setOnCloseEntryListener(entryCloseDispatcher)
        onDispose {
            navController.setOnCloseEntryListener(null)
        }
    }
    // display current entry + animate enter/exit
    AnimatedContent(
        targetState = navController.currentNavEntry,
        contentKey = { it?.destination?.name ?: "" },
        contentAlignment = Alignment.Center,
        modifier = modifier,
        transitionSpec = {
            when {
                navController.isInitialTransition -> ContentTransform(
                    targetContentEnter = EnterTransition.None,
                    initialContentExit = ExitTransition.None,
                    sizeTransform = null
                )

                navController.contentTransition != null -> navController.contentTransition!!
                else -> contentTransformProvider(navController.isForwardTransition)
            }
        },
        label = "nav_controller_${navController.key?.toString() ?: "no_key"}",
    ) {
        if (it != null) Box {
            // gen save state
            val saveRegistry = remember(it) {
                val registry = SaveableStateRegistry(it.savedState) { true }
                it.savedStateRegistry = registry
                registry
            }
            // gen storage
            val entryStorage = remember(it) {
                val storage = navController.dataStorage.data.getOrPut(it.storageKey()) {
                    val storage = DataStorage()
                    if (it.navArgs != null) storage.data[KEY_NAV_ARGS] = it.navArgs
                    storage
                } as DataStorage
                if (it.navResult != null) storage.data[KEY_NAV_RESULT] = it.navResult
                it.entryStorage = storage
                storage
            }
            // display content
            CompositionLocalProvider(
                LocalSaveableStateRegistry provides saveRegistry,
                LocalDataStore provides entryStorage,
                LocalNavController provides navController
            ) {
                DestinationContent(it.destination)
            }
            // prevent clicks during transition animation
            if (transition.isRunning) Overlay()
        }
    }
}

/**
 * Provides current [NavController] instance
 */
@Composable
@Suppress("UnusedReceiverParameter")
fun NavDestinationScope<*>.navController(): NavController =
    LocalNavController.current ?: error("not attached to navController")

/**
 * Provides nav arguments passed into navigate forward function for current destination
 *
 * @see [NavController.navigate]
 * @see [NavController.replace]
 *
 * @return navigation arguments provided to [NavController.navigate] function or exception
 */
@Composable
@Suppress("UNCHECKED_CAST", "CastToNullableType", "UnusedReceiverParameter")
fun <Args> NavDestinationScope<Args>.navArgs(): Args {
    val store = LocalDataStore.current ?: error("Store not bound")
    return remember { store.data[KEY_NAV_ARGS] as Args? }
        ?: error("args not provided or null, consider use navArgsOrNull()")
}

/**
 * Provides nav arguments passed into navigate forward function for current destination
 *
 * @see [NavController.navigate]
 * @see [NavController.replace]
 *
 * @return navigation arguments provided to [NavController.navigate] function or null
 */
@Composable
@Suppress("UNCHECKED_CAST", "CastToNullableType", "UnusedReceiverParameter")
fun <Args> NavDestinationScope<Args>.navArgsOrNull(): Args? {
    val store = LocalDataStore.current ?: error("Store not bound")
    return remember { store.data[KEY_NAV_ARGS] as Args? }
}

/**
 * Provides nav arguments provided as result into navigate back function for current destination
 *
 * @see [NavController.back]
 */
@Composable
@Suppress("UNCHECKED_CAST", "CastToNullableType", "UnusedReceiverParameter")
fun <Result> NavDestinationScope<*>.navResult(): Result? {
    val store = LocalDataStore.current ?: error("Store not bound")
    val result = remember { store.data[KEY_NAV_RESULT] as Result? }
    return result
}

/**
 * Provide (create or restore) viewModel instance bound to navigation entry
 *
 * @param key provides unique key to create viewModel
 * @param provider default viewModel instance provider
 */
@Composable
@Suppress("UNCHECKED_CAST", "CastToNullableType", "UnusedReceiverParameter")
fun <Model : ComposeViewModel> NavDestinationScope<*>.rememberViewModel(
    key: String? = null,
    provider: () -> Model
): Model {
    val store = LocalDataStore.current ?: error("Store not bound")
    val compositionHash = currentCompositeKeyHash
    return remember {
        val storeKey = "Model#" + (key ?: compositionHash.toString(16))
        store.data.getOrPut(storeKey, provider) as Model
    }
}