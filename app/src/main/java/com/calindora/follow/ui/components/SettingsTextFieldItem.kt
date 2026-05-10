package com.calindora.follow.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.calindora.follow.R

@Composable
fun SettingsTextFieldItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    validate: (String) -> String? = { null },
) {
  var passwordVisible by remember { mutableStateOf(false) }
  val interactionSource = remember { MutableInteractionSource() }
  val isFocused by interactionSource.collectIsFocusedAsState()
  val error = if (!isFocused) validate(value) else null

  val fieldModifier =
      modifier.fillMaxWidth().let {
        if (focusRequester != null) it.focusRequester(focusRequester) else it
      }

  val hidePasswordDescription = stringResource(R.string.action_hide_password)
  val showPasswordDescription = stringResource(R.string.action_show_password)

  OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      label = { Text(label) },
      modifier = fieldModifier,
      interactionSource = interactionSource,
      singleLine = true,
      visualTransformation =
          if (isPassword && !passwordVisible) PasswordVisualTransformation()
          else VisualTransformation.None,
      trailingIcon =
          if (isPassword) {
            {
              val painter =
                  if (passwordVisible) {
                    painterResource(R.drawable.visibility_24px)
                  } else {
                    painterResource(R.drawable.visibility_off_24px)
                  }

              val description =
                  if (passwordVisible) hidePasswordDescription else showPasswordDescription

              IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(painter = painter, contentDescription = description)
              }
            }
          } else null,
      keyboardOptions = keyboardOptions,
      keyboardActions = keyboardActions,
      isError = error != null,
      supportingText = error?.let { { Text(it) } },
  )
}
