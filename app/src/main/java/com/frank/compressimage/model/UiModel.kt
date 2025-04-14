package com.frank.compressimage.model

import androidx.annotation.Keep

@Keep
data class UiModel(
    // 是否保持文件修改时间，true：读取源文件修改日期并写入；false：使用程序运行时间
    // 默认是true
    var isKeepModifyTime: Boolean = true,
    // 压缩成功后是否删除源文件，节省空间
    var isDeleteSrcFile: Boolean = false,
    // 目标路径存在同名文件，是否替换
    var replaceIfExist: Boolean = false,
)