/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.instantexecution

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.artifacts.configurations.DefaultConfigurationContainer
import org.gradle.api.internal.project.DefaultProject
import org.gradle.api.internal.tasks.DefaultTaskContainer
import org.gradle.api.invocation.Gradle
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.initialization.DefaultSettings
import org.gradle.initialization.LoadProjectsBuildOperationType
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.invocation.DefaultGradle
import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.workers.WorkerExecutor
import org.junit.Rule
import org.slf4j.Logger
import spock.lang.Ignore
import spock.lang.Unroll

import javax.inject.Inject

class InstantExecutionIntegrationTest extends AbstractInstantExecutionIntegrationTest {

    def "instant execution for help on empty project"() {
        given:
        instantRun "help"
        def firstRunOutput = result.normalizedOutput.replace('Calculating task graph as no instant execution cache is available for tasks: help', '')

        when:
        instantRun "help"

        then:
        firstRunOutput == result.normalizedOutput.replace('Reusing instant execution cache. This is not guaranteed to work in any way.', '')
    }

    def "restores some details of the project structure"() {
        def fixture = new BuildOperationsFixture(executer, temporaryFolder)

        settingsFile << """
            rootProject.name = 'thing'
        """

        when:
        instantRun "help"

        then:
        def event = fixture.first(LoadProjectsBuildOperationType)
        event.result.rootProject.name == 'thing'

        when:
        instantRun "help"

        then:
        def event2 = fixture.first(LoadProjectsBuildOperationType)
        event2.result.rootProject.name == 'thing'
    }

    def "does not configure build when task graph is already cached for requested tasks"() {

        def instantExecution = newInstantExecutionFixture()

        given:
        buildFile << """
            println "running build script"
            
            class SomeTask extends DefaultTask {
                SomeTask() {
                    println("create task")
                }
            }
            task a(type: SomeTask) {
                println("configure task")
            }
            task b {
                dependsOn a
            }
        """

        when:
        instantRun "a"

        then:
        instantExecution.assertStateStored()
        outputContains("Calculating task graph as no instant execution cache is available for tasks: a")
        outputContains("running build script")
        outputContains("create task")
        outputContains("configure task")
        result.assertTasksExecuted(":a")

        when:
        instantRun "a"

        then:
        instantExecution.assertStateLoaded()
        outputContains("Reusing instant execution cache. This is not guaranteed to work in any way.")
        outputDoesNotContain("running build script")
        outputDoesNotContain("create task")
        outputDoesNotContain("configure task")
        result.assertTasksExecuted(":a")

        when:
        instantRun "b"

        then:
        instantExecution.assertStateStored()
        outputContains("Calculating task graph as no instant execution cache is available for tasks: b")
        outputContains("running build script")
        outputContains("create task")
        outputContains("configure task")
        result.assertTasksExecuted(":a", ":b")

        when:
        instantRun "a"

        then:
        instantExecution.assertStateLoaded()
        outputContains("Reusing instant execution cache. This is not guaranteed to work in any way.")
        outputDoesNotContain("running build script")
        outputDoesNotContain("create task")
        outputDoesNotContain("configure task")
        result.assertTasksExecuted(":a")
    }

    @Rule
    BlockingHttpServer server = new BlockingHttpServer()

    def "instant execution for task in multiple projects"() {
        server.start()

        given:
        settingsFile << """
            include 'a', 'b', 'c'
        """
        buildFile << """
            class SlowTask extends DefaultTask {
                @TaskAction
                def go() {
                    ${server.callFromBuildUsingExpression("project.name")}
                }
            }

            subprojects {
                tasks.create('slow', SlowTask)
            }
            project(':a') {
                tasks.slow.dependsOn(project(':b').tasks.slow, project(':c').tasks.slow)
            }
        """

        when:
        server.expectConcurrent("b", "c")
        server.expectConcurrent("a")
        instantRun "slow", "--parallel"

        then:
        noExceptionThrown()

        when:
        def pendingCalls = server.expectConcurrentAndBlock("b", "c")
        server.expectConcurrent("a")

        def buildHandle = executer.withTasks("slow", "--parallel", "--max-workers=3", INSTANT_EXECUTION_PROPERTY).start()
        pendingCalls.waitForAllPendingCalls()
        pendingCalls.releaseAll()
        buildHandle.waitForFinish()

        then:
        noExceptionThrown()
    }

    def "instant execution for multi-level projects"() {
        given:
        settingsFile << """
            include 'a:b', 'a:c'
        """
        instantRun ":a:b:help", ":a:c:help"
        def firstRunOutput = result.groupedOutput

        when:
        instantRun ":a:b:help", ":a:c:help"

        then:
        result.groupedOutput.task(":a:b:help").output == firstRunOutput.task(":a:b:help").output
        result.groupedOutput.task(":a:c:help").output == firstRunOutput.task(":a:c:help").output
    }

    def "restores task fields whose value is an object graph with cycles"() {
        buildFile << """
            class SomeBean {
                String value 
                SomeBean parent
                SomeBean child
                
                SomeBean(String value) {
                    println("creating bean")
                    this.value = value
                }
            }

            class SomeTask extends DefaultTask {
                final SomeBean bean
                
                SomeTask() {
                    bean = new SomeBean("default")
                    bean.parent = new SomeBean("parent")
                    bean.parent.child = bean
                    bean.parent.parent = bean.parent
                }

                @TaskAction
                void run() {
                    println "bean.value = " + bean.value
                    println "bean.parent.value = " + bean.parent.value
                    println "same reference = " + (bean.parent.child == bean)
                }
            }

            task ok(type: SomeTask) {
                bean.value = "child"
            }
        """

        when:
        instantRun "ok"

        then:
        result.output.count("creating bean") == 2

        when:
        instantRun "ok"

        then:
        outputDoesNotContain("creating bean")
        outputContains("bean.value = child")
        outputContains("bean.parent.value = parent")
        outputContains("same reference = true")
    }

    @Unroll
    def "restores task fields whose value is instance of #type"() {
        buildFile << """
            class SomeBean {
                ${type} value 
            }
            
            enum SomeEnum {
                One, Two
            }

            class SomeTask extends DefaultTask {
                private final SomeBean bean = new SomeBean()
                private final ${type} value
                
                SomeTask() {
                    value = ${reference}
                    bean.value = ${reference}
                }

                @TaskAction
                void run() {
                    println "this.value = " + value
                    println "bean.value = " + bean.value
                }
            }

            task ok(type: SomeTask)
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("this.value = ${output}")
        outputContains("bean.value = ${output}")

        where:
        type                             | reference                                                     | output
        String.name                      | "'value'"                                                     | "value"
        String.name                      | "null"                                                        | "null"
        Boolean.name                     | "true"                                                        | "true"
        boolean.name                     | "true"                                                        | "true"
        Character.name                   | "'a'"                                                         | "a"
        char.name                        | "'a'"                                                         | "a"
        Byte.name                        | "12"                                                          | "12"
        byte.name                        | "12"                                                          | "12"
        Short.name                       | "12"                                                          | "12"
        short.name                       | "12"                                                          | "12"
        Integer.name                     | "12"                                                          | "12"
        int.name                         | "12"                                                          | "12"
        Long.name                        | "12"                                                          | "12"
        long.name                        | "12"                                                          | "12"
        Float.name                       | "12.1"                                                        | "12.1"
        float.name                       | "12.1"                                                        | "12.1"
        Double.name                      | "12.1"                                                        | "12.1"
        double.name                      | "12.1"                                                        | "12.1"
        Class.name                       | "SomeBean"                                                    | "class SomeBean"
        "SomeEnum"                       | "SomeEnum.Two"                                                | "Two"
        "SomeEnum[]"                     | "[SomeEnum.Two] as SomeEnum[]"                                | "[Two]"
        "List<String>"                   | "['a', 'b', 'c']"                                             | "[a, b, c]"
        "ArrayList<String>"              | "['a', 'b', 'c'] as ArrayList"                                | "[a, b, c]"
        "LinkedList<String>"             | "['a', 'b', 'c'] as LinkedList"                               | "[a, b, c]"
        "Set<String>"                    | "['a', 'b', 'c'] as Set"                                      | "[a, b, c]"
        "HashSet<String>"                | "['a', 'b', 'c'] as HashSet"                                  | "[a, b, c]"
        "LinkedHashSet<String>"          | "['a', 'b', 'c'] as LinkedHashSet"                            | "[a, b, c]"
        "TreeSet<String>"                | "['a', 'b', 'c'] as TreeSet"                                  | "[a, b, c]"
        "EnumSet<SomeEnum>"              | "EnumSet.of(SomeEnum.Two)"                                    | "[Two]"
        "Map<String, Integer>"           | "[a: 1, b: 2]"                                                | "[a:1, b:2]"
        "HashMap<String, Integer>"       | "new HashMap([a: 1, b: 2])"                                   | "[a:1, b:2]"
        "LinkedHashMap<String, Integer>" | "new LinkedHashMap([a: 1, b: 2])"                             | "[a:1, b:2]"
        "TreeMap<String, Integer>"       | "new TreeMap([a: 1, b: 2])"                                   | "[a:1, b:2]"
        "EnumMap<SomeEnum, String>"      | "new EnumMap([(SomeEnum.One): 'one', (SomeEnum.Two): 'two'])" | "[One:one, Two:two]"
    }

    @Unroll
    def "restores task fields whose value is instance of plugin specific version of Guava #type"() {
        buildFile << """
            import ${type.name}

            buildscript {
                repositories {
                    jcenter()
                }
                dependencies {
                    classpath 'com.google.guava:guava:28.0-jre' 
                }
            }

            class SomeBean {
                ${type.simpleName} value 
            }

            class SomeTask extends DefaultTask {
                private final SomeBean bean = new SomeBean()
                private final ${type.simpleName} value
                
                SomeTask() {
                    value = ${reference}
                    bean.value = ${reference}
                }

                @TaskAction
                void run() {
                    println "this.value = " + value
                    println "bean.value = " + bean.value
                }
            }

            task ok(type: SomeTask)
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("this.value = ${output}")
        outputContains("bean.value = ${output}")

        where:
        type          | reference                         | output
        ImmutableList | "ImmutableList.of('a', 'b', 'c')" | "[a, b, c]"
        ImmutableSet  | "ImmutableSet.of('a', 'b', 'c')"  | "[a, b, c]"
        ImmutableMap  | "ImmutableMap.of(1, 'a', 2, 'b')" | "[1:a, 2:b]"
    }

    def "restores task fields whose value is Serializable and has writeReplace method"() {
        buildFile << """
            class Placeholder implements Serializable {
                String value
                
                private Object readResolve() {
                    return new OtherBean(prop: "[\$value]")
                } 
            }

            class OtherBean implements Serializable {
                String prop

                private Object writeReplace() {
                    return new Placeholder(value: prop)
                }
            }

            class SomeBean {
                OtherBean value 
            }

            class SomeTask extends DefaultTask {
                private final SomeBean bean = new SomeBean()
                private final OtherBean value
                
                SomeTask() {
                    value = new OtherBean(prop: 'a')
                    bean.value = new OtherBean(prop: 'b')
                }

                @TaskAction
                void run() {
                    println "this.value = " + value.prop
                    println "bean.value = " + bean.value.prop
                }
            }

            task ok(type: SomeTask)
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("this.value = [a]")
        outputContains("bean.value = [b]")
    }

    @Unroll
    def "restores task fields whose value is service of type #type"() {
        buildFile << """
            class SomeBean {
                ${type} value 
            }

            class SomeTask extends DefaultTask {
                final SomeBean bean = new SomeBean()
                ${type} value

                @TaskAction
                void run() {
                    value.${invocation}
                    bean.value.${invocation}
                }
            }

            task ok(type: SomeTask) {
                value = ${reference}
                bean.value = ${reference}
            }
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        noExceptionThrown()

        where:
        type                             | reference                                                   | invocation
        Logger.name                      | "logger"                                                    | "info('hi')"
        ObjectFactory.name               | "objects"                                                   | "newInstance(SomeBean)"
        ToolingModelBuilderRegistry.name | "project.services.get(${ToolingModelBuilderRegistry.name})" | "toString()"
        WorkerExecutor.name              | "project.services.get(${WorkerExecutor.name})"              | "noIsolation()"
    }

    @Unroll
    def "restores task fields whose value is provider of type #type"() {
        buildFile << """
            import ${Inject.name}

            class SomeBean {
                ${type} value
            }

            class SomeTask extends DefaultTask {
                final SomeBean bean = project.objects.newInstance(SomeBean)
                ${type} value

                @TaskAction
                void run() {
                    println "this.value = " + value.getOrNull()
                    println "bean.value = " + bean.value.getOrNull()
                }
            }

            task ok(type: SomeTask) {
                value = ${reference}
                bean.value = ${reference}
            }
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("this.value = ${output}")
        outputContains("bean.value = ${output}")

        where:
        type               | reference                                 | output
        "Provider<String>" | "providers.provider { 'value' }"          | "value"
        "Provider<String>" | "providers.provider { null }"             | "null"
        "Provider<String>" | "objects.property(String).value('value')" | "value"
        "Provider<String>" | "objects.property(String)"                | "null"
    }

    @Unroll
    def "restores task fields whose value is broken #type"() {
        def instantExecution = newInstantExecutionFixture()

        buildFile << """
            import ${Inject.name}

            class SomeTask extends DefaultTask {
                ${type} value = ${reference} { throw new RuntimeException("broken!") }

                @TaskAction
                void run() {
                    println "this.value = " + value.${query}
                }
            }

            task broken(type: SomeTask) {
            }
        """

        when:
        instantFails "broken"
        instantFails "broken"

        then:
        instantExecution.assertStateLoaded()
        failure.assertTasksExecuted(":broken")
        failure.assertHasDescription("Execution failed for task ':broken'.")
        failure.assertHasCause("broken!")

        where:
        type               | reference                    | query
        "Provider<String>" | "project.providers.provider" | "get()"
        "FileCollection"   | "project.files"              | "files"
    }

    @Unroll
    def "restores task fields whose value is property of type #type"() {
        buildFile << """
            import ${Inject.name}

            class SomeBean {
                final ${type} value

                @Inject
                SomeBean(ObjectFactory objects) {
                    value = ${factory}
                }
            }

            class SomeTask extends DefaultTask {
                final SomeBean bean = project.objects.newInstance(SomeBean)
                final ${type} value

                @Inject
                SomeTask(ObjectFactory objects) {
                    value = ${factory}
                }

                @TaskAction
                void run() {
                    println "this.value = " + value.getOrNull()
                    println "bean.value = " + bean.value.getOrNull()
                }
            }

            task ok(type: SomeTask) {
                value = ${reference}
                bean.value = ${reference}
            }
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        def expected = output instanceof File ? file(output.path) : output
        outputContains("this.value = ${expected}")
        outputContains("bean.value = ${expected}")

        where:
        type                          | factory                               | reference        | output
        "Property<String>"            | "objects.property(String)"            | "'value'"        | "value"
        "Property<String>"            | "objects.property(String)"            | "null"           | "null"
        "DirectoryProperty"           | "objects.directoryProperty()"         | "file('abc')"    | new File('abc')
        "DirectoryProperty"           | "objects.directoryProperty()"         | "null"           | "null"
        "RegularFileProperty"         | "objects.fileProperty()"              | "file('abc')"    | new File('abc')
        "RegularFileProperty"         | "objects.fileProperty()"              | "null"           | "null"
        "ListProperty<String>"        | "objects.listProperty(String)"        | "[]"             | "[]"
        "ListProperty<String>"        | "objects.listProperty(String)"        | "['abc']"        | ['abc']
        "MapProperty<String, String>" | "objects.mapProperty(String, String)" | "[:]"            | [:]
        "MapProperty<String, String>" | "objects.mapProperty(String, String)" | "['abc': 'def']" | ['abc': 'def']
    }

    @Unroll
    def "restores task fields whose value is a serializable #kind Java lambda"() {
        given:
        file("buildSrc/src/main/java/my/LambdaTask.java").tap {
            parentFile.mkdirs()
            text = """
                package my;

                import org.gradle.api.*;
                import org.gradle.api.tasks.*;

                public class LambdaTask extends DefaultTask {

                    public interface SerializableSupplier<T> extends java.io.Serializable {
                        T get();
                    }

                    private SerializableSupplier<Integer> supplier;

                    public void setSupplier(SerializableSupplier<Integer> supplier) {
                        this.supplier = supplier;
                    }

                    public void setNonInstanceCapturingLambda() {
                        final int i = getName().length();
                        setSupplier(() -> i);
                    }

                    public void setInstanceCapturingLambda() {
                        setSupplier(() -> getName().length());
                    }

                    @TaskAction
                    void printValue() {
                        System.out.println("this.supplier.get() -> " + this.supplier.get());
                    }
                }
            """
        }

        buildFile << """
            task ok(type: my.LambdaTask) {
                $expression
            }
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("this.supplier.get() -> 2")

        where:
        kind                     | expression
        "instance capturing"     | "setInstanceCapturingLambda()"
        "non-instance capturing" | "setNonInstanceCapturingLambda()"
    }

    @Unroll
    def "warns when task field references an object of type #baseType"() {
        buildFile << """
            class SomeBean {
                private ${baseType} badReference
            }
            
            class SomeTask extends DefaultTask {
                private final ${baseType} badReference
                private final bean = new SomeBean()
                
                SomeTask() {
                    badReference = ${reference}
                    bean.badReference = ${reference}
                }

                @TaskAction
                void run() {
                    println "this.reference = " + badReference
                    println "bean.reference = " + bean.badReference
                }
            }

            task other
            task broken(type: SomeTask)
        """

        when:
        instantRun "broken"

        then:
        outputContains("instant-execution > cannot serialize object of type '${concreteType}', a subtype of '${baseType}', as these are not supported with instant execution.")

        when:
        instantRun "broken"

        then:
        outputContains("this.reference = null")
        outputContains("bean.reference = null")

        where:
        concreteType                       | baseType                    | reference
        DefaultProject.name                | Project.name                | "project"
        DefaultGradle.name                 | Gradle.name                 | "project.gradle"
        DefaultSettings.name               | Settings.name               | "project.gradle.settings"
        DefaultTask.name                   | Task.name                   | "project.tasks.other"
        DefaultTaskContainer.name          | TaskContainer.name          | "project.tasks"
        DefaultConfigurationContainer.name | ConfigurationContainer.name | "project.configurations"
    }

    def "restores task abstract properties"() {
        buildFile << """

            abstract class SomeTask extends DefaultTask {

                abstract Property<String> getValue()

                @TaskAction
                void run() {
                    println "this.value = " + value.getOrNull()
                }
            }

            task ok(type: SomeTask) {
                value = "42"
            }
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("this.value = 42")
    }

    def "task can reference itself"() {
        buildFile << """
            class SomeBean {
                private SomeTask owner
            }
            
            class SomeTask extends DefaultTask {
                private final SomeTask thisTask
                private final bean = new SomeBean()
                
                SomeTask() {
                    thisTask = this
                    bean.owner = this
                }

                @TaskAction
                void run() {
                    println "thisTask = " + (thisTask == this) 
                    println "bean.owner = " + (bean.owner == this)
                }
            }

            task ok(type: SomeTask)
        """

        when:
        instantRun "ok"
        instantRun "ok"

        then:
        outputContains("thisTask = true")
        outputContains("bean.owner = true")
    }

    @Ignore("wip")
    def "reuses cached ClassLoaders"() {

        given: 'a Task that holds some static data'
        def staticDataLib = file("lib/StaticData.jar").tap {
            parentFile.mkdirs()
        }
        jarWithClasses(
            staticDataLib,
            StaticData: """
                import org.gradle.api.*;
                import org.gradle.api.tasks.*;
                import java.util.concurrent.atomic.AtomicInteger;

                public class StaticData extends DefaultTask {

                    private static final AtomicInteger value = new AtomicInteger(0);

                    @TaskAction
                    void printValue() {
                        // When ClassLoaders are reused
                        // the 1st run should print `<project name>.value = 1`
                        // the 2nd run should print `<project name>.value = 2`
                        // and so on.
                        System.out.println(getProject().getName() + ".value = " + value.incrementAndGet());
                    }
                }
            """
        )

        and: "multiple sub-projects"
        settingsFile << """
            include 'foo:foo'
            include 'bar:bar'
        """

        // Make the classpath of :foo differ from :bar's
        // thus causing :foo:foo and :bar:bar to have separate ClassLoaders.
        def someLib = file('lib/someLib.jar')
        jarWithClasses(someLib, SomeClass: 'class SomeClass {}')

        file("foo/build.gradle") << """
            buildscript { dependencies { classpath(files('${someLib.toURI()}')) } }
        """

        // Load the StaticData class in the different sub-sub-projects
        // for a more interesting ClassLoader hierarchy.
        for (projectDir in ['foo/foo', 'bar/bar']) {
            file("$projectDir/build.gradle") << """
                buildscript { dependencies { classpath(files('${staticDataLib.toURI()}')) } }

                task ok(type: StaticData)
            """
        }

        when:
        instantRun ":foo:foo:ok", ":bar:bar:ok"

        then:
        outputContains("foo.value = 1")
        outputContains("bar.value = 1")

        when:
        instantRun ":foo:foo:ok", ":bar:bar:ok"

        then:
        outputContains("foo.value = 2")
        // TODO:instant-execution currently, when loading from the instant execution cache,
        //  a single CachingClassLoader is used to serve all the classes,
        // see `DefaultInstantExecution.classLoaderFor(List<ClassLoaderScopeSpec>): ClassLoader` for details,
        // and because of that, :bar:bar:ok ends up using the same class as :foo:foo:ok and the final value is
        // `3` instead of `2` as it would be the case with classic execution.
        // Once the original ClassLoader structure is honoured the expection should be:
        // outputContains("bar.value = 2")
        // In the meantime:
        outputContains("bar.value = 3")
    }

}
