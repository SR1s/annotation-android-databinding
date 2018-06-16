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

package android.databinding.tool.processing.scopes;

/**
 * Base class for all scopes
 * 
 * Scope代表一个区域，可以是一个文件，如子接口FileScopeProvider
 * 也可以是文本内的某些区域，如子接口LocationScopeProvider
 * DataBinding框架在处理的过程中，会通过这些Provider记录当前处理到了哪些区域
 * 如若在处理的时候发生异常，就通过这种方式获取到区域的位置，记录异常和源文件导致异常区域之间的联系
 * 这部分是DataBinding用来解决出现异常后，异常信息不友好的问题的一个方式
 */
public interface ScopeProvider {

}
