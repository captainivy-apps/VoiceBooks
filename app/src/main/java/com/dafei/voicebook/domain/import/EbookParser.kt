package com.dafei.voicebook.domain.import

import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.model.ParsedBook
import java.io.File

interface EbookParser {
    fun parse(file: File, format: BookFormat): ParsedBook
}
