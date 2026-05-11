package com.calindora.follow

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Filename-safe timestamp for diagnostic log files. */
val LOG_FILE_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault())
