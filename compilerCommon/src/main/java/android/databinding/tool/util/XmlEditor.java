/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.databinding.parser.BindingExpressionBaseVisitor;
import android.databinding.parser.BindingExpressionLexer;
import android.databinding.parser.BindingExpressionParser;
import android.databinding.parser.XMLLexer;
import android.databinding.parser.XMLParser;
import android.databinding.parser.XMLParser.AttributeContext;
import android.databinding.parser.XMLParser.ElementContext;

import com.google.common.base.Joiner;
import com.google.common.xml.XmlEscapers;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Ugly inefficient class to strip unwanted tags from XML.
 * Band-aid solution to unblock development
 * 
 * 如注释所说，这个类是用来除去xml中不想要的tags(把不想要的都替换成空格)
 * 如：<data>、<layout>、含有表达式的属性
 * 最终输出一份新的布局文件，这份文件才是android开发框架认可的
 */
public class XmlEditor {

    /**
     * 使用这个方法，把databinding布局还原成android开发框架使用的原始的布局文件
     * 
     * @param f 输入文件，就是 databinding 的布局文件
     * @param newTag layout/<layout_file_name> 正常情况下这个会赋值给根节点的android:tag属性
     * @param encoding 编码
     */
    public static String strip(File f, String newTag, String encoding) throws IOException {
        // 这一长串是为了构造解析器，然后解析出节点
        FileInputStream fin = new FileInputStream(f);
        InputStreamReader reader = new InputStreamReader(fin, encoding);
        ANTLRInputStream inputStream = new ANTLRInputStream(reader);
        XMLLexer lexer = new XMLLexer(inputStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        XMLParser parser = new XMLParser(tokenStream);
        XMLParser.DocumentContext expr = parser.document();
        ElementContext root = expr.element();

        // 检查根节点，databinding的根节点是 <layout>
        if (root == null || !"layout".equals(nodeName(root))) {
            return null; // not a binding layout
        }

        // 获取根节点的子节点
        List<? extends ElementContext> childrenOfRoot = elements(root);
        // 在子节点里找 <data> 节点
        List<? extends ElementContext> dataNodes = filterNodesByName("data", childrenOfRoot);
        // <data> 节点只允许有一个
        if (dataNodes.size() > 1) {
            L.e("Multiple binding data tags in %s. Expecting a maximum of one.",
                    f.getAbsolutePath());
        }

        // 这一步把databinding的布局文件还原成正常的布局文件
        ArrayList<String> lines = new ArrayList<String>();
        lines.addAll(FileUtils.readLines(f, encoding));

        // 把 <data> 节点的内容都替换成空格
        for (ElementContext it : dataNodes) {
            replace(lines, toPosition(it.getStart()), toEndPosition(it.getStop()), "");
        }

        // 移除列表中的 <data> 节点
        List<? extends ElementContext> layoutNodes =
                excludeNodesByName("data", childrenOfRoot);
        // databinding的布局里，只有一个 <data> 节点和 布局根节点
        if (layoutNodes.size() != 1) {
            L.e("Only one layout element and one data element are allowed. %s has %d",
                    f.getAbsolutePath(), layoutNodes.size());
        }

        // 布局节点
        final ElementContext layoutNode = layoutNodes.get(0);

        ArrayList<TagAndContext> noTag = new ArrayList<TagAndContext>();

        // 递归地替换节点中的表达式属性为带默认值的普通属性
        // 没添加tag的节点，都存入了noTag这个列表里
        recurseReplace(layoutNode, lines, noTag, newTag, 0);

        // Remove the <layout>
        // 移除 <layout> 标签到子节点的开始区域
        // 注意，<data> 标签的区域在前面已经都替换成空格了
        Position rootStartTag = toPosition(root.getStart());
        Position rootEndTag = toPosition(root.content().getStart());
        replace(lines, rootStartTag, rootEndTag, "");

        // Remove the </layout>
        // 移除 </layout>
        // 经过着一系列处理，现在就只剩下布局的部分了
        PositionPair endLayoutPositions = findTerminalPositions(root, lines);
        replace(lines, endLayoutPositions.left, endLayoutPositions.right, "");

        // 这里还要把原来 <layout> 节点上的那些属性，主要是命名空间
        // 挪到改造后的布局的根节点上

        // 先把属性弄出来
        StringBuilder rootAttributes = new StringBuilder();
        for (AttributeContext attr : attributes(root)) {
            rootAttributes.append(' ').append(attr.getText());
        }

        // 查找noTag list里是否包含根节点
        TagAndContext noTagRoot = null;
        for (TagAndContext tagAndContext : noTag) {
            if (tagAndContext.getContext() == layoutNode) {
                noTagRoot = tagAndContext;
                break;
            }
        }

        if (noTagRoot != null) {
            // noTag list中包含根节点
            // 新创建了一个对象，更新了tag内容，加上了<layout>根节点的属性
            // 然后替换了noTag列表里原来的对象
            // todo 为什么是直接添加，好奇怪
            TagAndContext newRootTag = new TagAndContext(
                    noTagRoot.getTag() + rootAttributes.toString(), layoutNode);
            int index = noTag.indexOf(noTagRoot);
            noTag.set(index, newRootTag);
        } else {
            // noTag list中不包含根节点
            // 不包含的情况就简单了
            // 新创建一个对象，以<layout>根节点的属性为tag
            // 然后直接加到noTag列表的最后
            TagAndContext newRootTag =
                    new TagAndContext(rootAttributes.toString(), layoutNode);
            noTag.add(newRootTag);
        }
        //noinspection NullableProblems
        // 进行排序，经过排序之后，节点的顺序就变成按节点在文档里的出现的先后顺序排序的了
        Collections.sort(noTag, new Comparator<TagAndContext>() {
            @Override
            public int compare(TagAndContext o1, TagAndContext o2) {
                Position start1 = toPosition(o1.getContext().getStart());
                Position start2 = toPosition(o2.getContext().getStart());
                // 先比较行数
                int lineCmp = start2.line - start1.line;
                if (lineCmp != 0) {
                    return lineCmp;
                }
                // 同一行的情况下，在比较在行内的起始位置
                return start2.charIndex - start1.charIndex;
            }
        });
        for (TagAndContext it : noTag) {
            ElementContext element = it.getContext();
            String tag = it.getTag();
            Position endTagPosition = endTagPosition(element);
            fixPosition(lines, endTagPosition);
            String line = lines.get(endTagPosition.line);
            // 在文本的对应位置，其实就是节点的末尾加上tag这个字段
            String newLine = line.substring(0, endTagPosition.charIndex) + " " + tag +
                    line.substring(endTagPosition.charIndex);
            // 更新列表里的数据
            lines.set(endTagPosition.line, newLine);
        }

        // 重新输出一份文本
        return Joiner.on(StringUtils.LINE_SEPARATOR).join(lines);
    }

    /**
     * 在节点列表里查找 指定tag 的节点
     */
    private static <T extends XMLParser.ElementContext> List<T>
            filterNodesByName(String name, Iterable<T> items) {
        List<T> result = new ArrayList<T>();
        for (T item : items) {
            if (name.equals(nodeName(item))) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 把指定tag节点从列表中移除，生成新的列表
     */
    private static <T extends XMLParser.ElementContext> List<T>
            excludeNodesByName(String name, Iterable<T> items) {
        List<T> result = new ArrayList<T>();
        for (T item : items) {
            if (!name.equals(nodeName(item))) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 转换token起始位置的信息到Position数据对象中
     */
    private static Position toPosition(Token token) {
        return new Position(token.getLine() - 1, token.getCharPositionInLine());
    }

    /**
     * 转换token终止位置的信息到Position数据对象中
     * todo 这里隐含了token的全部字符在一行里 (废话，不在一行就组不成一个token了)
     */
    private static Position toEndPosition(Token token) {
        return new Position(token.getLine() - 1,
                token.getCharPositionInLine() + token.getText().length());
    }

    /**
     * 获取节点的tag todo 这部分应该抽取到工具类
     */
    public static String nodeName(ElementContext elementContext) {
        return elementContext.elmName.getText();
    }

    /**
     * 获取节点上的属性集
     */
    public static List<? extends AttributeContext> attributes(ElementContext elementContext) {
        if (elementContext.attribute() == null)
            return new ArrayList<AttributeContext>();
        else {
            return elementContext.attribute();
        }
    }

    /**
     * 过滤并输出 是表达式的属性
     */
    public static List<? extends AttributeContext> expressionAttributes(
            ElementContext elementContext) {
        List<AttributeContext> result = new ArrayList<AttributeContext>();
        for (AttributeContext input : attributes(elementContext)) {
            String attrName = input.attrName.getText();
            // 如果属性名为android:tag，这个属性就是表达式
            // todo 这点没搞懂，大概是这个属性是特殊的？
            boolean isExpression = attrName.equals("android:tag");

            // 检查完属性名，接下来检查属性值
            if (!isExpression) {
                final String value = input.attrValue.getText();
                isExpression = isExpressionText(input.attrValue.getText());
            }
            // 是表达式，加入到列表里
            if (isExpression) {
                result.add(input);
            }
        }
        return result;
    }

    /**
     * 检查输入的value是否是表达式
     * databinding的约定里，表达式以"@{"、"@={"开头，以"}"结尾
     * 这里没有对表达式是否为有效的表达式做检查
     */
    private static boolean isExpressionText(String value) {
        // Check if the expression ends with "}" and starts with "@{" or "@={", ignoring
        // the surrounding quotes.
        return (value.length() > 5 && value.charAt(value.length() - 2) == '}' &&
                ("@{".equals(value.substring(1, 3)) || "@={".equals(value.substring(1, 4))));
    }

    /**
     * 查找tag的文本的最后一个位置
     * <layout> </layout>这个算两个tag
     * <layout>的最后一个位置在 >前面
     * <layout />的最后一个位置在 />前面
     * 
     * todo 
     * 也就说<layout></layout>这种拿到的content != null
     * 也就说<layout />这种拿到的content == null
     * context 表示的是一个节点的所有信息
     * context.getStop() 节点的最后一个位置
     */
    private static Position endTagPosition(ElementContext context) {
        if (context.content() == null) {
            // no content, so just choose the start of the "/>"
            // 没有内容的情况，选择 "/>"的位置
            Position endTag = toPosition(context.getStop());
            if (endTag.charIndex <= 0) {
                // 这种情况是xml不合法了
                L.e("invalid input in %s", context);
            }
            return endTag;
        } else {
            // tag with no attributes, but with content
            // 有内容的情况
            // 取的是content的start
            // 最后 start--得到 >的位置
            Position position = toPosition(context.content().getStart());
            if (position.charIndex <= 0) {
                L.e("invalid input in %s", context);
            }
            position.charIndex--;
            return position;
        }
    }

    /**
     * 获取节点下的所有子节点
     * 这里保证了非空 notNull
     */
    public static List<? extends ElementContext> elements(ElementContext context) {
        if (context.content() != null && context.content().element() != null) {
            return context.content().element();
        }
        return new ArrayList<ElementContext>();
    }

    private static boolean replace(ArrayList<String> lines, Position start, Position end,
            String text) {
        fixPosition(lines, start);
        fixPosition(lines, end);
        if (start.line != end.line) {
            // start行和end行不在同一行
            String startLine = lines.get(start.line);
            // 截取出start行中要保留的部分
            String newStartLine = startLine.substring(0, start.charIndex) + text;
            // 用新的newStartLine行替代原来的startLine行
            lines.set(start.line, newStartLine);
            // 把start和end之间的行都替换成空格
            for (int i = start.line + 1; i < end.line; i++) {
                String line = lines.get(i);
                lines.set(i, replaceWithSpaces(line, 0, line.length() - 1));
            }
            String endLine = lines.get(end.line);
            // 把end行内，position之前的部分都替换成空格，即那些都是不要的部分
            String newEndLine = replaceWithSpaces(endLine, 0, end.charIndex - 1);
            // 替换成新行
            lines.set(end.line, newEndLine);
            return true;
        } else if (end.charIndex - start.charIndex >= text.length()) {
            // start行和end行是同一行
            // 这种情况则是做替换，即取出中间不要的那部分，然后换成text
            String line = lines.get(start.line);
            int endTextIndex = start.charIndex + text.length();
            // 前面的部分替换成text
            String replacedText = replaceRange(line, start.charIndex, endTextIndex, text);
            // 后面多出来的行替换成空格
            String spacedText = replaceWithSpaces(replacedText, endTextIndex, end.charIndex - 1);
            lines.set(start.line, spacedText);
            return true;
        } else {
            // start和end是同一行，但空间不足以插入text
            String line = lines.get(start.line);
            // 简单的把起始位置和终止位置之间的部分替换成空格
            // 然后整体替换掉这一行，不做插入text
            String newLine = replaceWithSpaces(line, start.charIndex, end.charIndex - 1);
            lines.set(start.line, newLine);
            return false;
        }
    }

    /**
     * 替换line中的 start -> end之间的部分为 newText
     */
    private static String replaceRange(String line, int start, int end, String newText) {
        return line.substring(0, start) + newText + line.substring(end);
    }

    /**
     * 节点是否含有表达式属性
     */
    public static boolean hasExpressionAttributes(ElementContext context) {
        // 查找表达式属性列表
        List<? extends AttributeContext> expressions = expressionAttributes(context);
        int size = expressions.size();
        if (size == 0) {
            // 列表为空，节点不包含表达式属性
            return false;
        } else if (size > 1) {
            // 多于1条记录，包含表达式属性
            return true;
        } else {
            // 不论何种情况，android:tag都会作为表达式属性被包含进来
            // 因此，我们只能在android:tag拥有binding表达式的时候，才将它算成真正的表达式属性
            // todo 这一段好像搞复杂了
            // android:tag is included, regardless, so we must only count as an expression
            // if android:tag has a binding expression.
            return isExpressionText(expressions.get(0).attrValue.getText());
        }
    }

    /**
     * 递归地替换节点中的表达式属性为带默认值的普通属性
     */
    private static int recurseReplace(ElementContext node, ArrayList<String> lines,
            ArrayList<TagAndContext> noTag,
            String newTag, int bindingIndex) {
        int nextBindingIndex = bindingIndex;
        // 是否是 <merge> 开头的根节点
        boolean isMerge = "merge".equals(nodeName(node));
        // 布局内是否包含 <include> 节点
        final boolean containsInclude = filterNodesByName("include", elements(node)).size() > 0;
        if (!isMerge && (hasExpressionAttributes(node) || newTag != null || containsInclude)) {
            // 不是 merge 节点，且同时满足以下三个条件之一：1. 节点有表达式；2. 有自定义tag；3.包含<include>节点
            String tag = "";
            // 设置tag，形式为 android:tag="<tag_prefix>_<index>"
            if (newTag != null) {
                // 自定义的tag_prefix的情况
                // 无差别都用上这种
                tag = "android:tag=\"" + newTag + "_" + bindingIndex + "\"";
                nextBindingIndex++;
            } else if (!"include".equals(nodeName(node))) {
                // 没有自定义tag_prefix时
                // 只给非 include 节点加tag
                tag = "android:tag=\"binding_" + bindingIndex + "\"";
                nextBindingIndex++;
            }
            for (AttributeContext it : expressionAttributes(node)) {
                Position start = toPosition(it.getStart());
                Position end = toEndPosition(it.getStop());
                // 获取属性的默认值
                String defaultVal = defaultReplacement(it);
                if (defaultVal != null) {
                    // 有默认值的情况，把包含表达式的属性，替换成不包含表达式的属性，用默认值取代表达式
                    replace(lines, start, end, it.attrName.getText() + "=\"" + defaultVal + "\"");
                } else if (replace(lines, start, end, tag)) {
                    // 没有默认值的情况，尝试用tag替换掉属性
                    // tag设为空字符，避免循环的过程中，下一个没有默认值的属性，又重复添加了tag
                    // todo 这个android:tag的标记的作用是什么？
                    tag = "";
                }
            }
            // 出现没有替换tag的可能情况有：
            // 1. 没有表达式属性(循环就没有进去)
            // 2. replace失败的情况(start和end是同一行，但空间不足以插入text)
            if (tag.length() != 0) {
                // tag长度不为0
                // 表示属性都能用默认值来代替
                // 也意味着节点没有设置tag，即noTag Node
                // 添加到列表里，记录在案
                // todo 这个noTagList是用来干什么的呢？
                // 本来这个tag应该加到节点上去的
                // 却没有加上去，先记录下来
                noTag.add(new TagAndContext(tag, node));
            }
        }

        String nextTag;
        if (bindingIndex == 0 && isMerge) {
            // 首次，且根节点为merge节点时
            // 这个newTag没有用过，透传给下一个节点
            // todo 是这个意思吗？
            // todo 难不成根节点的tag有特殊含义，有那么点印象
            nextTag = newTag;
        } else {
            // 非merge节点或非第一个节点，即根节点
            // nextTag 统一设置为null
            nextTag = null;
        }
        
        // 对节点的子节点做同样的操作（递归）
        for (ElementContext it : elements(node)) {
            nextBindingIndex = recurseReplace(it, lines, noTag, nextTag, nextBindingIndex);
        }
        // 这个nextBindingIndex每遍历一个节点就+1
        // 返回下一个bindingIndex的起始索引
        return nextBindingIndex;
    }

    private static String defaultReplacement(XMLParser.AttributeContext attr) {
        // 包含引号的文本
        String textWithQuotes = attr.attrValue.getText();
        // 移除了引号的文本
        String escapedText = textWithQuotes.substring(1, textWithQuotes.length() - 1);
        // 是否双向绑定
        final boolean isTwoWay = escapedText.startsWith("@={");
        // 是否单向绑定
        final boolean isOneWay = escapedText.startsWith("@{");
        if ((!isTwoWay && !isOneWay) || !escapedText.endsWith("}")) {
            // 无效的属性（即非表达式）的情况下返回null
            return null;
        }
        final int startIndex = isTwoWay ? 3 : 2;
        final int endIndex = escapedText.length() - 1;
        // 截取出实际的表达式内容，把其中的xml编码的字符转义回来
        String text = StringUtils.unescapeXml(escapedText.substring(startIndex, endIndex));
        
        // 把表达式经过一系列的构造，交给了文本解析器处理
        ANTLRInputStream inputStream = new ANTLRInputStream(text);
        BindingExpressionLexer lexer = new BindingExpressionLexer(inputStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        BindingExpressionParser parser = new BindingExpressionParser(tokenStream);

        // todo 以下部分设计ANTLR的使用，没看懂
        BindingExpressionParser.BindingSyntaxContext root = parser.bindingSyntax();
        BindingExpressionParser.DefaultsContext defaults = root
                .accept(new BindingExpressionBaseVisitor<BindingExpressionParser.DefaultsContext>() {
                    @Override
                    public BindingExpressionParser.DefaultsContext visitDefaults(
                            @NotNull BindingExpressionParser.DefaultsContext ctx) {
                        return ctx;
                    }
                });
        if (defaults != null) {
            BindingExpressionParser.ConstantValueContext constantValue = defaults
                    .constantValue();
            BindingExpressionParser.LiteralContext literal = constantValue.literal();
            if (literal != null) {
                BindingExpressionParser.StringLiteralContext stringLiteral = literal
                        .stringLiteral();
                if (stringLiteral != null) {
                    TerminalNode doubleQuote = stringLiteral.DoubleQuoteString();
                    if (doubleQuote != null) {
                        String quotedStr = doubleQuote.getText();
                        String unquoted = quotedStr.substring(1, quotedStr.length() - 1);
                        return XmlEscapers.xmlAttributeEscaper().escape(unquoted);
                    } else {
                        String quotedStr = stringLiteral.SingleQuoteString().getText();
                        String unquoted = quotedStr.substring(1, quotedStr.length() - 1);
                        String unescaped = unquoted.replace("\"", "\\\"").replace("\\`”", "`");
                        return XmlEscapers.xmlAttributeEscaper().escape(unescaped);
                    }
                }
            }
            return constantValue.getText();
        }
        return null;
    }

    private static PositionPair findTerminalPositions(ElementContext node,
            ArrayList<String> lines) {
        Position endPosition = toEndPosition(node.getStop());
        Position startPosition = toPosition(node.getStop());
        int index;
        do {
            index = lines.get(startPosition.line).lastIndexOf("</");
            startPosition.line--;
        } while (index < 0);
        startPosition.line++;
        startPosition.charIndex = index;
        //noinspection unchecked
        return new PositionPair(startPosition, endPosition);
    }

    /**
     * 替换原有line内的内容为空格
     */
    private static String replaceWithSpaces(String line, int start, int end) {
        StringBuilder lineBuilder = new StringBuilder(line);
        // todo 为什么不直接替换成空行
        for (int i = start; i <= end; i++) {
            lineBuilder.setCharAt(i, ' ');
        }
        return lineBuilder.toString();
    }

    /**
     * 保证position的取值是有效的
     * 主要是position指向的行的字符位置在position整行的长度内
     * 即 0 <= charIndex <= line.length
     */
    private static void fixPosition(ArrayList<String> lines, Position pos) {
        String line = lines.get(pos.line);
        // todo 为什么不直接赋值成line.length
        while (pos.charIndex > line.length()) {
            pos.charIndex--;
        }
    }

    /**
     * 位置信息数据对象
     */
    private static class Position {

        int line;
        int charIndex;

        public Position(int line, int charIndex) {
            this.line = line;
            this.charIndex = charIndex;
        }
    }

    /**
     * 节点名和节点数据结构的包装数据类
     */
    private static class TagAndContext {
        private final String mTag;
        private final ElementContext mElementContext;

        private TagAndContext(String tag, ElementContext elementContext) {
            mTag = tag;
            mElementContext = elementContext;
        }

        private ElementContext getContext() {
            return mElementContext;
        }

        private String getTag() {
            return mTag;
        }
    }

    private static class PositionPair {
        private final Position left;
        private final Position right;

        private PositionPair(Position left, Position right) {
            this.left = left;
            this.right = right;
        }
    }
}
