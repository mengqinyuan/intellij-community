/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.codeInsight.generation;

import com.intellij.psi.PsiClass;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface PropertyClassMember extends EncapsulatableClassMember {
  /**
   * @return PsiElement or TemplateGenerationInfo
   * @deprecated please, use {@link PropertyClassMember#generateGetters(PsiClass, SetterGetterGenerationOptions)}
   */
  @Deprecated
  GenerationInfo @Nullable [] generateGetters(PsiClass aClass) throws IncorrectOperationException;

  /**
   * @return PsiElement or TemplateGenerationInfo
   * @deprecated please, use {@link PropertyClassMember#generateSetters(PsiClass, SetterGetterGenerationOptions)}
   */
  @Deprecated
  GenerationInfo @Nullable [] generateSetters(PsiClass aClass) throws IncorrectOperationException;

  /**
   * @return PsiElement or TemplateGenerationInfo
   */
  default GenerationInfo @Nullable [] generateGetters(@NotNull PsiClass aClass, @NotNull SetterGetterGenerationOptions options) throws IncorrectOperationException{
    return generateGetters(aClass);
  }

  /**
   * @return PsiElement or TemplateGenerationInfo
   */
  default GenerationInfo @Nullable [] generateSetters(@NotNull PsiClass aClass, @NotNull SetterGetterGenerationOptions options) throws IncorrectOperationException{
    return generateSetters(aClass);
  }
}
