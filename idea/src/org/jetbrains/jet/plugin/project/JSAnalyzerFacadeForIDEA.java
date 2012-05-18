/*
 * Copyright 2010-2012 JetBrains s.r.o.
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

package org.jetbrains.jet.plugin.project;

import com.google.common.base.Predicate;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.analyzer.AnalyzeExhaust;
import org.jetbrains.jet.analyzer.AnalyzerFacade;
import org.jetbrains.jet.lang.cfg.pseudocode.JetControlFlowDataTraceFactory;
import org.jetbrains.jet.lang.psi.JetFile;
import org.jetbrains.jet.lang.resolve.BindingContext;
import org.jetbrains.jet.lang.types.lang.JetStandardLibrary;
import org.jetbrains.k2js.analyze.AnalyzerFacadeForJS;

import java.util.Collection;

/**
 * @author Pavel Talanov
 */
public enum JSAnalyzerFacadeForIDEA implements AnalyzerFacade {

    INSTANCE;

    private JSAnalyzerFacadeForIDEA() {
    }

    @NotNull
    @Override
    public AnalyzeExhaust analyzeFiles(@NotNull Project project,
                                       @NotNull Collection<JetFile> files,
                                       @NotNull Predicate<PsiFile> filesToAnalyzeCompletely,
                                       @NotNull JetControlFlowDataTraceFactory flowDataTraceFactory) {
        BindingContext context = AnalyzerFacadeForJS.analyzeFiles(files, filesToAnalyzeCompletely, new IDEAConfig(project));
        return AnalyzeExhaust.success(context, JetStandardLibrary.getInstance());
    }
}