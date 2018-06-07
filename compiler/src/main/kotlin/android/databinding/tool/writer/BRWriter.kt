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

package android.databinding.tool.writer

import android.databinding.tool.util.StringUtils

class BRWriter(properties: Set<String>, val useFinal : Boolean) {
    val indexedProps = properties.sorted().withIndex()
    /**
     * 返回一串字符串（实际上是BR.java类的代码）
     * 格式：
     * package <pkg>
     * <klass>
     *
     * kclass是动态生成的代码
     * kcode也是很有意思的代码实现，见KCode
     */
    public fun write(pkg : String): String = "package $pkg;${StringUtils.LINE_SEPARATOR}$klass"
    
    /**
     * kcode定义了语义化生成代码的DSL
     * 这里是使用这个DSL来生成BR.java的代码
     * BR的意思大概是 BindingResources 绑定资源的索引文件
     */
    val klass: String by lazy {
        kcode("") { // 生成新的代码块
            // 这一段是普通的表达式
            val prefix = if (useFinal) "final " else "";
            block("public class BR") { // 新起一个代码块 {  }
                tab("public static ${prefix}int _all = 0;")
                indexedProps.forEach {
                    tab ("public static ${prefix}int ${it.value} = ${it.index + 1};")
                }
            }
        }.generate()
    }
}
