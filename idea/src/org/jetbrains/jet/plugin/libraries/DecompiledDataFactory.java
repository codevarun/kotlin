/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.libraries;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.compiled.ClsAnnotationImpl;
import com.intellij.psi.impl.compiled.ClsElementImpl;
import com.intellij.psi.impl.compiled.ClsFileImpl;
import com.intellij.psi.util.PsiTreeUtil;
import jet.runtime.typeinfo.JetClass;
import jet.runtime.typeinfo.JetMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.di.InjectorForJavaSemanticServices;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.psi.JetDeclaration;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.resolve.BindingContextUtils;
import org.jetbrains.jet.lang.resolve.java.DescriptorSearchRule;
import org.jetbrains.jet.lang.resolve.java.JavaDescriptorResolver;
import org.jetbrains.jet.lang.resolve.java.JvmAbi;
import org.jetbrains.jet.lang.resolve.name.FqName;
import org.jetbrains.jet.resolve.DescriptorRenderer;

import java.util.*;

/**
 * @author Evgeny Gerashchenko
 * @since 3/11/12
 */
class DecompiledDataFactory {
    private static final String JET_CLASS = JetClass.class.getName();
    private static final String JET_METHOD = JetMethod.class.getName();
    private static final String DECOMPILED_COMMENT = "/* compiled code */";

    private final StringBuilder builder = new StringBuilder();
    private final ClsFileImpl clsFile;
    private final BindingContext bindingContext;
    private final Map<PsiElement, TextRange> clsMembersToRanges = new HashMap<PsiElement, TextRange>();

    private final Map<ClsElementImpl, JetDeclaration> clsElementsToJetElements = new HashMap<ClsElementImpl, JetDeclaration>();
    private final JavaDescriptorResolver javaDescriptorResolver;

    private DecompiledDataFactory(ClsFileImpl clsFile) {
        this.clsFile = clsFile;
        Project project = this.clsFile.getProject();
        InjectorForJavaSemanticServices injector = new InjectorForJavaSemanticServices(project);
        bindingContext = injector.getBindingTrace().getBindingContext();
        javaDescriptorResolver = injector.getJavaDescriptorResolver();
    }

    @NotNull
    static JetDecompiledData createDecompiledData(@NotNull ClsFileImpl clsFile) {
        return new DecompiledDataFactory(clsFile).build();
    }

    private JetDecompiledData build() {
        builder.append("// IntelliJ API Decompiler stub source generated from a class file\n" +
                         "// Implementation of methods is not available");
        builder.append("\n\n");

        String packageName = clsFile.getPackageName();
        if (packageName.length() > 0) {
            builder.append("package ").append(packageName).append("\n\n");
        }

        PsiClass psiClass = clsFile.getClasses()[0];

        if (isKotlinNamespaceClass(psiClass)) {
            NamespaceDescriptor nd = javaDescriptorResolver.resolveNamespace(new FqName(packageName), DescriptorSearchRule.INCLUDE_KOTLIN);

            if (nd != null) {
                for (DeclarationDescriptor member : sortDeclarations(nd.getMemberScope().getAllDescriptors())) {
                    if (member instanceof ClassDescriptor || member instanceof NamespaceDescriptor) {
                        continue;
                    }
                    appendDescriptor(member, "");
                    builder.append("\n");
                }
            }
        }
        else {
            ClassDescriptor cd = javaDescriptorResolver.resolveClass(new FqName(psiClass.getQualifiedName()), DescriptorSearchRule.INCLUDE_KOTLIN);
            if (cd != null) {
                appendDescriptor(cd, "");
            }
        }

        JetFile jetFile = JetDummyClassFileViewProvider.createJetFile(clsFile.getManager(), clsFile.getVirtualFile(), builder.toString());
        for (Map.Entry<PsiElement, TextRange> clsMemberToRange : clsMembersToRanges.entrySet()) {
            PsiElement clsMember = clsMemberToRange.getKey();
            assert clsMember instanceof ClsElementImpl;

            TextRange range = clsMemberToRange.getValue();
            JetDeclaration jetDeclaration = PsiTreeUtil.findElementOfClassAtRange(jetFile, range.getStartOffset(), range.getEndOffset(),
                                                                                  JetDeclaration.class);
            assert jetDeclaration != null : "Can't find declaration at " + range + ": "
                                            + jetFile.getText().substring(range.getStartOffset(), range.getEndOffset());
            clsElementsToJetElements.put((ClsElementImpl) clsMember, jetDeclaration);
        }

        return new JetDecompiledData(jetFile, clsElementsToJetElements);
    }

    private static List<DeclarationDescriptor> sortDeclarations(Collection<DeclarationDescriptor> input) {
        ArrayList<DeclarationDescriptor> r = new ArrayList<DeclarationDescriptor>(input);
        Collections.sort(r, new DeclarationDescriptorComparator());
        return r;
    }

    private void appendDescriptor(@NotNull DeclarationDescriptor descriptor, String indent) {
        int startOffset = builder.length();
        String renderedDescriptor = DescriptorRenderer.COMPACT_WITH_MODIFIERS.render(descriptor);
        renderedDescriptor = renderedDescriptor.replace("= ...", "= " + DECOMPILED_COMMENT);
        builder.append(renderedDescriptor);
        int endOffset = builder.length();

        if (descriptor instanceof FunctionDescriptor || descriptor instanceof PropertyDescriptor) {
            if (((CallableMemberDescriptor) descriptor).getModality() != Modality.ABSTRACT) {
                if (descriptor instanceof FunctionDescriptor) {
                    builder.append(" { ").append(DECOMPILED_COMMENT).append(" }");
                    endOffset = builder.length();
                }
                else { // descriptor instanceof PropertyDescriptor
                    if (((PropertyDescriptor) descriptor).getModality() != Modality.ABSTRACT) {
                        builder.append(" ").append(DECOMPILED_COMMENT);
                    }
                }
            }
        }
        else if (descriptor instanceof ClassDescriptor) {
            builder.append(" {\n");
            ClassDescriptor classDescriptor = (ClassDescriptor) descriptor;
            boolean firstPassed = false;
            String subindent = indent + "    ";
            if (classDescriptor.getClassObjectDescriptor() != null) {
                firstPassed = true;
                builder.append(subindent);
                appendDescriptor(classDescriptor.getClassObjectDescriptor(), subindent);
            }
            for (DeclarationDescriptor member : sortDeclarations(classDescriptor.getDefaultType().getMemberScope().getAllDescriptors())) {
                if (member.getContainingDeclaration() != descriptor) {
                    continue;
                }
                if (member instanceof CallableMemberDescriptor && ((CallableMemberDescriptor) member).getKind() != CallableMemberDescriptor.Kind.DECLARATION) {
                    continue;
                }

                if (firstPassed) {
                    builder.append("\n");
                }
                else {
                    firstPassed = true;
                }
                builder.append(subindent);
                appendDescriptor(member, subindent);
            }
            builder.append(indent).append("}");
            endOffset = builder.length();
        }

        builder.append("\n");
        PsiElement clsMember = BindingContextUtils.descriptorToDeclaration(bindingContext, descriptor);
        if (clsMember != null) {
            clsMembersToRanges.put(clsMember, new TextRange(startOffset, endOffset));
        }
    }

    private static boolean hasAnnotation(PsiModifierListOwner modifierListOwner, String qualifiedName) {
        PsiModifierList modifierList = modifierListOwner.getModifierList();
        if (modifierList != null) {
            for (PsiAnnotation annotation : modifierList.getAnnotations()) {
                if (annotation instanceof ClsAnnotationImpl) {
                    if (qualifiedName.equals(annotation.getQualifiedName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean isKotlinClass(PsiClass psiClass) {
        return hasAnnotation(psiClass, JET_CLASS);
    }

    static boolean isKotlinNamespaceClass(PsiClass psiClass) {
        if (JvmAbi.PACKAGE_CLASS.equals(psiClass.getName()) && !isKotlinClass(psiClass)) {
            for (PsiMethod method : psiClass.getMethods()) {
                if (hasAnnotation(method, JET_METHOD)) {
                    return true;
                }
            }
        }
        return false;
    }
}
