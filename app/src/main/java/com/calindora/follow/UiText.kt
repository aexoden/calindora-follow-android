package com.calindora.follow

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * A localized message described as a resource reference rather than a resolved string, so it can
 * survive configuration changes in ViewModel state and be rendered by any UI surface.
 */
sealed interface UiText {
  fun resolve(context: Context): String

  data class Simple(@param:StringRes val resId: Int) : UiText {
    override fun resolve(context: Context): String = context.getString(resId)
  }

  data class Plural(@param:PluralsRes val resId: Int, val quantity: Int) : UiText {
    override fun resolve(context: Context): String =
        context.resources.getQuantityString(resId, quantity, quantity)
  }
}
