/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.tool.util;

/**
 * 简单的两个工具方法
 * 1. 给定一个字符串，以"-"或"_"切割，首字母大写转成类名(驼峰法)
 * 2. 给定字符串，去除拓展名，没有的话，则返回原字符串
 */
public class ParserHelper {
    public static String toClassName(String name) {
        StringBuilder builder = new StringBuilder();
        for (String item : name.split("[_-]")) {
            builder.append(StringUtils.capitalize(item));
        }
        return builder.toString();
    }

    public static String stripExtension(String name) {
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
