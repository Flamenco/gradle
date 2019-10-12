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

package org.gradle.testing.junitplatform

class JUnitPlatformLoggingIntegrationTest extends JUnitPlatformIntegrationSpec  {

    def "should log display names"() {
        given:
        buildFile << """
            test {
                testLogging {
                    events "passed", "skipped", "failed"
                }
            }
        """
        file("src/test/java/TopLevelClass.java")  << """
            import org.junit.jupiter.api.DisplayName;
            import org.junit.jupiter.api.Nested;
            import org.junit.jupiter.api.Test;

            @DisplayName("Class level display name")
            public class TopLevelClass {

                @Nested
                @DisplayName("Nested class display name")
                public class NestedClass {

                    @Test
                    @DisplayName("Nested test method display name")
                    public void nestedTestMethod() {
                    }
                }

                @Test
                @DisplayName("Method display name")
                public void testMethod() {
                }

                @Test
                public void noDisplayName() {
                }
            }
         """

        when:
        def result = run("test")

        then:
        result.output.contains("Class level display name > noDisplayName()")
        result.output.contains("Class level display name > Method display name")
        result.output.contains("Class level display name > Nested class display name > Nested test method display name")
    }
}
