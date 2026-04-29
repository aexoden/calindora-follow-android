package com.calindora.follow

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/** A toast message described as a resource reference rather than a resolved string. */
sealed interface ToastMessage {
  fun resolve(context: Context): String

  data class Simple(@param:StringRes val resId: Int) : ToastMessage {
    override fun resolve(context: Context): String = context.getString(resId)
  }

  data class Plural(@param:PluralsRes val resId: Int, val quantity: Int) : ToastMessage {
    override fun resolve(context: Context): String =
        context.resources.getQuantityString(resId, quantity, quantity)
  }
}
