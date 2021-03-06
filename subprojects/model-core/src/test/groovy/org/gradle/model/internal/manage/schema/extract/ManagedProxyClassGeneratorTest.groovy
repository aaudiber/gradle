/*
 * Copyright 2014 the original author or authors.
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

package org.gradle.model.internal.manage.schema.extract
import com.google.common.base.Optional
import com.google.common.collect.ImmutableMap
import org.gradle.model.internal.core.MutableModelNode
import org.gradle.model.internal.manage.instance.ManagedInstance
import org.gradle.model.internal.manage.instance.ModelElementState
import org.gradle.model.internal.manage.schema.ModelProperty
import org.gradle.model.internal.manage.schema.ModelProperty.StateManagementType
import org.gradle.model.internal.method.WeaklyTypeReferencingMethod
import org.gradle.model.internal.type.ModelType
import spock.lang.Ignore
import spock.lang.Specification
import spock.lang.Unroll

import static org.gradle.model.internal.manage.schema.ModelProperty.StateManagementType.*

class ManagedProxyClassGeneratorTest extends Specification {
    static def generator = new ManagedProxyClassGenerator()
    static Map<Class<?>, Map<Class<?>, Class<?>>> generated = [:].withDefault { [:] }

    def "generates a proxy class for an interface"() {
        expect:
        def impl = newInstance(SomeType)
        impl instanceof SomeType
    }

    def "generates a proxy class for an interface with type parameters"() {
        when:
        def generatedType = generate(SomeTypeWithParameters)

        then:
        generatedType.getMethod("getValues").returnType == List
        generatedType.getMethod("getValues").genericReturnType.actualTypeArguments == [String]

        generatedType.getMethod("getOptional").returnType == Optional
        generatedType.getMethod("getOptional").genericReturnType.actualTypeArguments == [Boolean]

        generatedType.getMethod("setOptional", Optional).genericParameterTypes*.actualTypeArguments == [[Boolean]]
    }

    def "mixes in ManagedInstance"() {
        def node = Stub(MutableModelNode)
        def state = Mock(ModelElementState) {
            getBackingNode() >> node
        }

        when:
        Class<? extends SomeType> proxyClass = generate(SomeType)
        SomeType impl = proxyClass.newInstance(state)

        then:
        assert impl instanceof ManagedInstance
        impl.backingNode == node
        impl.managedType == ModelType.of(SomeType)

        when:
        impl.value = 1
        then:
        1 * state.set("value", 1)

        when:
        def value = impl.value
        then:
        value == 1
        1 * state.get("value") >> { 1 }
    }

    def "equals() returns false for non-compatible types"() {
        def impl = newInstance(SomeType)
        expect:
        !impl.equals(null)
        !impl.equals(1)
    }

    def "equals() works as expected"() {
        def node1 = Mock(MutableModelNode)
        def node2 = Mock(MutableModelNode)
        def state1 = Mock(ModelElementState) {
            getBackingNode() >> node1
        }
        def state1alternative = Mock(ModelElementState) {
            getBackingNode() >> node1
        }
        def state2 = Mock(ModelElementState) {
            getBackingNode() >> node2
        }

        when:
        Class<? extends SomeType> proxyClass = generate(SomeType)
        SomeType impl1 = proxyClass.newInstance(state1)
        SomeType impl1alternative = proxyClass.newInstance(state1alternative)
        SomeType impl2 = proxyClass.newInstance(state2)

        then:
        impl1.equals(impl1)
        impl1.equals(impl1alternative)
        !impl1.equals(impl2)
    }

    def "hashCode() works as expected"() {
        def node = Mock(MutableModelNode)
        def state = Mock(ModelElementState) {
            getBackingNode() >> node
        }

        when:
        Class<? extends SomeType> proxyClass = generate(SomeType)
        SomeType impl = proxyClass.newInstance(state)
        def hashCode = impl.hashCode()

        then:
        hashCode == 123
        1 * node.hashCode() >> 123
    }

    def "mixes in unmanaged delegate"() {
        def node = Stub(MutableModelNode)
        def state = Mock(ModelElementState) {
            getBackingNode() >> node
        }

        when:
        Class<? extends ManagedSubType> proxyClass = generate(ManagedSubType, InternalUnmanagedType)
        def unmanagedInstance = new UnmanagedImplType()
        ManagedSubType impl = proxyClass.newInstance(state, unmanagedInstance)

        then:
        impl instanceof ManagedInstance
        ((ManagedInstance) impl).backingNode == node

        when: impl.unmanagedValue = "Lajos"
        then: unmanagedInstance.unmanagedValue == "Lajos"

        when:
        def greeting = impl.sayHello()
        then:
        greeting == "Hello Lajos"

        expect:
        ((InternalUnmanagedType) impl).add(2, 3) == 5

        when:
        ((InternalUnmanagedType) impl).throwError()
        then:
        def ex = thrown RuntimeException
        ex.message == "error"

        when:
        impl.managedValue = "Tibor"
        then:
        1 * state.set("managedValue", "Tibor")

        when:
        def managedValue = impl.managedValue
        then:
        managedValue == "Tibor"
        1 * state.get("managedValue") >> { "Tibor" }
    }

    def "mixes in toString() implementation that delegates to element state"() {
        def state = Stub(ModelElementState) {
            getDisplayName() >> "<display-name>"
        }

        expect:
        def proxyClass = generate(SomeType)
        def impl = proxyClass.newInstance(state)
        impl.toString() == "<display-name>"
    }

    def "reports contract type rather than implementation class in groovy missing property error message"() {
        given:
        def impl = newInstance(SomeType)

        when:
        impl.unknown

        then:
        MissingPropertyException e = thrown()
        e.message == "No such property: unknown for class: ${SomeType.name}"

        when:
        impl.unknown = '12'

        then:
        e = thrown()
        e.message == "No such property: unknown for class: ${SomeType.name}"
    }

    @Ignore
    def "reports contract type rather than implementation class when attempting to set read-only property"() {
        given:
        def impl = newInstance(SomeTypeWithReadOnly)

        when:
        impl.readOnly = '12'

        then:
        ReadOnlyPropertyException e = thrown()
        e.message == "Cannot set readonly property: readOnly for class: ${SomeTypeWithReadOnly.name}"
    }

    def "reports contract type rather than implementation class when attempting to invoke unknown method"() {
        given:
        def impl = newInstance(SomeType)

        when:
        impl.unknown('12')

        then:
        MissingMethodException e = thrown()
        e.message.startsWith("No signature of method: ${SomeType.name}.unknown() is applicable")
    }

    def "reports contract type rather than implementation class when attempting to invoke method with unsupported parameters"() {
        given:
        def impl = newInstance(SomeType)

        when:
        impl.setValue('12')

        then:
        MissingMethodException e = thrown()
        e.message.startsWith("No signature of method: ${SomeType.name}.setValue() is applicable")
    }

    @Unroll
    def "can read and write #value to managed property of type #primitiveType"() {
        def loader = new GroovyClassLoader(getClass().classLoader)
        when:
        def interfaceWithPrimitiveProperty = loader.parseClass """
            interface PrimitiveProperty {
                $primitiveType.name getPrimitiveProperty()

                void setPrimitiveProperty($primitiveType.name value)
            }
        """


        def data = [:]
        def state = Mock(ModelElementState)
        state.get(_) >> { args->
            data[args[0]]
        }
        state.set(_, _) >> { args ->
            data[args[0]] = args[1]
        }
        def properties = [property(interfaceWithPrimitiveProperty, 'primitiveProperty', MANAGED)]
        def proxy = generate(interfaceWithPrimitiveProperty, null, properties)
        def instance = proxy.newInstance(state)

        then:
        new GroovyShell(loader,new Binding(instance:instance)).evaluate """
            instance.primitiveProperty = $value
            assert instance.primitiveProperty == $value
            instance
        """

        where:
        primitiveType | value
        byte          | "123"
        boolean       | "false"
        boolean       | "true"
        char          | "'c'"
        float         | "123.45f"
        long          | "123L"
        short         | "123"
        int           | "123"
        double        | "123.456d"
    }


    def <T> T newInstance(Class<T> type) {
        def generated = generate(type)
        return generated.newInstance(Stub(ModelElementState))
    }

    def <T, M extends T, D extends T> Class<? extends T> generate(Class<T> managedType, Class<D> delegateType = null, Collection<ModelPropertyExtractionResult<?>> properties = managedProperties[managedType]) {
        Map<Class<?>, Class<?>> generatedForDelegateType = generated[managedType]
        Class<? extends T> generated = generatedForDelegateType[delegateType] as Class<? extends T>
        if (generated == null) {
            generated = generator.generate(ModelType.of(managedType), delegateType, properties)
            generatedForDelegateType[delegateType] = generated
        }
        return generated
    }

    private static def property(Class<?> parentType, String name, StateManagementType stateManagementType) {
        def getter = parentType.getMethod("get" + name.capitalize());
        def type = ModelType.returnType(getter)
        def getterRef = new WeaklyTypeReferencingMethod(ModelType.of(parentType), type, getter)
        def getterContext = new PropertyAccessorExtractionContext([getter])
        def setterContext;
        try {
            def setter = parentType.getMethod("set" + name.capitalize(), type.getRawClass())
            setterContext = new PropertyAccessorExtractionContext([setter])
        } catch (ignore) {
            setterContext = null
        }
        return new ModelPropertyExtractionResult<?>(
            ModelProperty.of(type, name, stateManagementType, setterContext != null, Collections.emptySet(), getterRef),
            getterContext, setterContext)
    }

    static interface SomeType {
        Integer getValue()
        void setValue(Integer value)
    }

    static abstract class SomeTypeWithReadOnly {
        abstract Integer getValue()
        abstract void setValue(Integer value)
        String getReadOnly() {
            return "read-only"
        }
    }

    static interface PublicUnmanagedType {
        String getUnmanagedValue()
        void setUnmanagedValue(String unmanagedValue)
        String sayHello()
    }

    static interface InternalUnmanagedType extends PublicUnmanagedType {
        Integer add(Integer a, Integer b)
        void throwError()
    }

    static class UnmanagedImplType implements InternalUnmanagedType {
        String unmanagedValue

        @Override
        Integer add(Integer a, Integer b) {
            return a + b
        }

        @Override
        String sayHello() {
            return "Hello ${unmanagedValue}"
        }

        @Override
        void throwError() {
            throw new RuntimeException("error")
        }
    }

    static interface ManagedSubType extends PublicUnmanagedType {
        String getManagedValue()
        void setManagedValue(String managedValue)
    }

    static Map<Class<?>, Collection<ModelPropertyExtractionResult<?>>> managedProperties = ImmutableMap.builder()
        .put(SomeType, [
           property(SomeType, "value", MANAGED)
        ])
        .put(SomeTypeWithReadOnly, [
            property(SomeTypeWithReadOnly, "value", MANAGED),
            property(SomeTypeWithReadOnly, "readOnly", UNMANAGED)
        ])
        .put(PublicUnmanagedType, [
            property(PublicUnmanagedType, "unmanagedValue", UNMANAGED)
        ])
        .put(ManagedSubType, [
            property(ManagedSubType, "unmanagedValue", DELEGATED),
            property(ManagedSubType, "managedValue", MANAGED)
        ])
        .put(SomeTypeWithParameters, [
            property(SomeTypeWithParameters, "values", MANAGED),
            property(SomeTypeWithParameters, "optional", MANAGED)
        ])
        .build()
}
