/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.tasks.diagnostics

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.IvyRepository
import org.gradle.integtests.fixtures.MavenRepository

import static org.gradle.util.TextUtil.toPlatformLineSeparators

class DependencyReportTaskIntegrationTest extends AbstractIntegrationSpec {

    def repo = new MavenRepository(file("repo"))

    def setup() {
        distribution.requireOwnUserHomeDir()
    }

    def "omits repeated dependencies in case of circular dependencies"() {
        given:
        file("settings.gradle") << "include 'client', 'a', 'b', 'c'"

        [a: "b", b: "c", c: "a"].each { module, dep ->
            def upped = module.toUpperCase()
            file(module, "build.gradle") << """
                apply plugin: 'java'
                group = "group"
                version = 1.0

                dependencies {
                    compile project(":$dep")
                }
            """
            file(module, "src", "main", "java", "${upped}.java") << "public class $upped {}"
        }

        and:
        file("client", "build.gradle") << """
            apply plugin: 'java'
            
            dependencies {
                compile project(":a")
            }
        """
        
        when:
        run ":client:dependencies"
        
        then:
        output.contains "(*) - dependencies omitted (listed previously)"
    }

    def "fails if some dependencies are unresolved"() {
        given:
        file("build.gradle") << """
            configurations { foo }
            dependencies { foo 'i:dont:exist' }
        """

        when:
        runAndFail "dependencies"

        then:
        errorOutput.contains('Could not resolve all dependencies')
    }

    def "renders dependencies even if the configuration was already resolved"() {
        given:
        repo.module("foo", "bar", 1.0).publish()
        repo.module("foo", "bar", 2.0).publish()

        file("build.gradle") << """
            repositories {
                maven { url "${repo.uri}" }
            }
            configurations { foo }
            dependencies {
                foo 'foo:bar:1.0'
                foo 'foo:bar:2.0'
            }

            task resolveConf << {
                configurations.foo.each { println it }
            }
        """

        when:
        run "resolveConf", "dependencies"

        then:
        output.contains "foo:bar:1.0 -> 2.0"
    }

    def "renders selected versions in case of a conflict"() {
        given:
        repo.module("foo", "bar", 1.0).publish()
        repo.module("foo", "bar", 2.0).publish()
        repo.module("foo", "bar", 3.0).dependsOn('foo', 'baz', '5.0').publish()


        repo.module("foo", "baz", 5.0).publish()

        file("settings.gradle") << """include 'a', 'b', 'c', 'd', 'e'
rootProject.name = 'root'
"""

        file("build.gradle") << """
            allprojects {
                apply plugin: 'java'
                version = '1.0'
                repositories {
                    maven { url "${repo.uri}" }
                }
            }

            project(":a") {
               dependencies {
                    compile 'foo:bar:1.0'
                }
            }

            project(":b") {
               dependencies {
                    compile 'foo:bar:0.5.dont.exist'
                }
            }

            project(":c") {
               dependencies {
                    compile 'foo:bar:3.0'
               }
            }

            project(":d") {
               dependencies {
                    compile 'foo:bar:2.0'
               }
            }

            project(":e") {
               dependencies {
                    compile 'foo:bar:3.0'
               }
            }

            dependencies {
                compile project(":a"), project(":b"), project(":c"), project(":d"), project(":e")
            }
        """

        when:
        run ":dependencies"

        then:
        output.contains 'compile - Classpath for compiling the main sources.'

        output.contains(toPlatformLineSeparators("""
+--- root:a:1.0 [default]
|    \\--- foo:bar:1.0 -> 3.0 [default]
|         \\--- foo:baz:5.0 [compile,master,runtime]
+--- root:b:1.0 [default]
|    \\--- foo:bar:0.5.dont.exist -> 3.0 [default] (*)
+--- root:c:1.0 [default]
|    \\--- foo:bar:3.0 [default] (*)
+--- root:d:1.0 [default]
|    \\--- foo:bar:2.0 -> 3.0 [default] (*)
\\--- root:e:1.0 [default]
     \\--- foo:bar:3.0 [default] (*)
"""))
    }

    def "renders the dependency tree"() {
        given:
        repo.module("org", "leaf1").publish()
        repo.module("org", "leaf2").publish()
        repo.module("org", "leaf3").publish()
        repo.module("org", "leaf4").publish()

        repo.module("org", "middle1").dependsOn('leaf1', 'leaf2').publish()
        repo.module("org", "middle2").dependsOn('leaf3', 'leaf4').publish()

        repo.module("org", "toplevel").dependsOn("middle1", "middle2").publish()

        file("build.gradle") << """
            repositories {
                maven { url "${repo.uri}" }
            }

            configurations {
                conf
            }
            dependencies {
                conf 'org:toplevel:1.0'
            }
        """

        when:
        run ":dependencies"

        then:
        output.contains(toPlatformLineSeparators("""
\\--- org:toplevel:1.0 [default]
     +--- org:middle1:1.0 [compile,master,runtime]
     |    +--- org:leaf1:1.0 [compile,master,runtime]
     |    \\--- org:leaf2:1.0 [compile,master,runtime]
     \\--- org:middle2:1.0 [compile,master,runtime]
          +--- org:leaf3:1.0 [compile,master,runtime]
          \\--- org:leaf4:1.0 [compile,master,runtime]
"""))
    }

    def "shows selected versions in case of a multi-phase conflict"() {
        given:
        repo.module("foo", "foo", 1.0).publish()
        repo.module("foo", "foo", 2.0).publish()
        repo.module("foo", "foo", 3.0).publish()
        repo.module("foo", "foo", 4.0).publish()

        repo.module("bar", "bar", 5.0).dependsOn("foo", "foo", "4.0").publish()
        repo.module("bar", "bar", 6.0).dependsOn("foo", "foo", "3.0").publish()

        file("build.gradle") << """
            repositories {
                maven { url "${repo.uri}" }
            }

            configurations {
                conf
            }
            dependencies {
                conf 'bar:bar:5.0'
                conf 'bar:bar:6.0'
                conf 'foo:foo:1.0'
                conf 'foo:foo:2.0'
            }
        """

        when:
        run ":dependencies"

        then:
        output.contains(toPlatformLineSeparators("""
+--- bar:bar:5.0 -> 6.0 [default]
|    \\--- foo:foo:3.0 [compile,master,runtime]
+--- bar:bar:6.0 [default] (*)
+--- foo:foo:1.0 -> 3.0 [default] (*)
\\--- foo:foo:2.0 -> 3.0 [default] (*)
"""))
    }

    def "deals with dynamic versions with conflicts"() {
        given:
        repo.module("foo", "bar", 1.0).publish()
        repo.module("foo", "bar", 2.0).publish()

        repo.module("foo", "foo", 1.0).dependsOn("foo", "bar", "1.0").publish()
        repo.module("foo", "foo", 2.0).dependsOn("foo", "bar", "1.0").publish()
        repo.module("foo", "foo", 2.5).dependsOn("foo", "bar", "2.0").publish()

        file("build.gradle") << """
            repositories {
                maven { url "${repo.uri}" }
            }

            configurations {
                conf
            }
            dependencies {
                conf 'foo:foo:1+'
                conf 'foo:foo:2+'
            }
        """

        when:
        run ":dependencies"

        then:
        output.contains(toPlatformLineSeparators("""
+--- foo:foo:1+ -> 2.5 [default]
|    \\--- foo:bar:2.0 [compile,master,runtime]
\\--- foo:foo:2+ -> 2.5 [default] (*)
"""))
    }

    def "renders the configurations in predictable order"() {
        given:
        def repo = new IvyRepository(file("repo"))

        def module = repo.module("org", "child")
        module.configurations['first'] = [extendsFrom: ['second'], transitive: true]
        module.configurations['second'] = [extendsFrom: [], transitive: true]
        module.publish()

        module = repo.module("org", "parent").dependsOn('child')
        module.configurations['first'] = [extendsFrom: ['second'], transitive: true]
        module.configurations['second'] = [extendsFrom: [], transitive: true]
        module.publish()

        file("build.gradle") << """
            repositories {
                ivy { url "${repo.uri}" }
            }
            configurations {
                conf
            }
            dependencies {
                conf 'org:parent:1.0'
            }
        """

        when:
        run ":dependencies"

        then:
        output.contains "org:child:1.0 [runtime,default,first,second]"
    }

    def "renders the ivy tree with conflicts"() {
        given:
        def repo = new IvyRepository(file("repo"))

        repo.module("org", "leaf1").publish()
        repo.module("org", "leaf2").publish()
        repo.module("org", "leaf3").publish()
        repo.module("org", "leaf4").publish()
        repo.module("org", "leaf4", 2.0).publish()

        //also asserting on correct order of transitive dependencies
        repo.module("org", "middle1").dependsOn('leaf1', 'leaf2').publish()
        repo.module("org", "middle2").dependsOn('leaf3', 'leaf4') publish()

        repo.module("org", "toplevel").dependsOn("middle1", "middle2").publish()

        file("build.gradle") << """
            repositories {
                ivy { url "${repo.uri}" }
            }

            configurations {
                conf
            }
            dependencies {
                conf 'org:toplevel:1.0', 'org:leaf4:2.0'
            }
        """

        when:
        run ":dependencies"

        then:
        output.contains(toPlatformLineSeparators("""
+--- org:toplevel:1.0 [default]
|    +--- org:middle1:1.0 [runtime,default]
|    |    +--- org:leaf1:1.0 [runtime,default]
|    |    \\--- org:leaf2:1.0 [runtime,default]
|    \\--- org:middle2:1.0 [runtime,default]
|         +--- org:leaf3:1.0 [runtime,default]
|         \\--- org:leaf4:1.0 -> 2.0 [runtime,default]
\\--- org:leaf4:2.0 [default] (*)
"""))
    }
}