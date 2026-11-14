package com.notify.ui.components

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed as composeItemsIndexed
import androidx.compose.runtime.Composable

inline fun <T> LazyListScope.itemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, item: T) -> Any)? = null,
    crossinline contentType: (index: Int, item: T) -> Any? = { _, _ -> null },
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit
) {
    composeItemsIndexed(
        items = items,
        key = key,
        contentType = contentType,
        itemContent = itemContent
    )
}

