/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.node;

import static com.github.benmanes.caffeine.cache.Specifications.kTypeVar;
import static com.github.benmanes.caffeine.cache.Specifications.referenceType;
import static com.github.benmanes.caffeine.cache.node.NodeContext.varHandleName;

import javax.lang.model.element.Modifier;

import com.github.benmanes.caffeine.cache.node.NodeContext.Visibility;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;

/**
 * Adds the key to the node.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class AddKey implements NodeRule {

  @Override
  public boolean applies(NodeContext context) {
    return context.isBaseClass();
  }

  @Override
  public void execute(NodeContext context) {
    if (context.isStrongValues()) {
      addIfStrongValue(context);
    } else {
      addIfCollectedValue(context);
    }
  }

  private static void addIfStrongValue(NodeContext context) {
    var fieldSpec = context.isStrongKeys()
        ? FieldSpec.builder(kTypeVar, "key", Modifier.VOLATILE)
        : FieldSpec.builder(context.keyReferenceType(), "key", Modifier.VOLATILE);
    context.nodeSubtype
        .addField(fieldSpec.build())
        .addMethod(context.newGetter(context.keyStrength(), kTypeVar, "key", Visibility.PLAIN))
        .addMethod(context.newGetRef("key"));
    context.addVarHandle("key", context.isStrongKeys()
        ? ClassName.get(Object.class)
        : context.keyReferenceType().rawType);
  }

  private static void addIfCollectedValue(NodeContext context) {
    context.nodeSubtype.addMethod(MethodSpec.methodBuilder("getKeyReference")
        .addComment("The plain read here does not observe a partially constructed or out-of-order "
            + "write because the release store ensures a happens-before relationship, making all "
            + "prior writes visible to threads that subsequently read the variable at any access "
            + "strength. This guarantees that any observed instance is fully constructed. However, "
            + "because the plain read lacks acquire semantics, it does not guarantee observing the "
            + "most recent value. The plain read may return either the previous or the newly "
            + "published instance, depending on the timing of the read relative to the write.")
        .addModifiers(context.publicFinalModifiers())
        .returns(Object.class)
        .addStatement("$1T valueRef = ($1T) $2L.get(this)",
            context.valueReferenceType(), varHandleName("value"))
        .addStatement("return valueRef.getKeyReference()")
        .build());

    var getKey = MethodSpec.methodBuilder("getKey")
        .addModifiers(context.publicFinalModifiers())
        .returns(kTypeVar)
        .addStatement("$1T valueRef = ($1T) $2L.get(this)",
            context.valueReferenceType(), varHandleName("value"));
    if (context.isStrongKeys()) {
      getKey.addStatement("return ($T) valueRef.getKeyReference()", kTypeVar);
    } else {
      getKey.addStatement("$1T keyRef = ($1T) valueRef.getKeyReference()", referenceType);
      getKey.addStatement("return keyRef.get()");
    }
    context.nodeSubtype.addMethod(getKey.build());
    context.suppressedWarnings.add("unchecked");
  }
}
