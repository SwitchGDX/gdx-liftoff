package gdx.liftoff.data.platforms

import gdx.liftoff.data.files.CopiedFile
import gdx.liftoff.data.files.gradle.GradleFile
import gdx.liftoff.data.files.path
import gdx.liftoff.data.libraries.Repository
import gdx.liftoff.data.project.Project
import gdx.liftoff.views.GdxPlatform

/**
 * Represents Switch homebrew project
 */
@GdxPlatform
class Switch : Platform {
  companion object {
    const val ID = "switch"
    const val ORDER = iOSMOE.ORDER + 1
  }

  override val id = ID
  override val order = ORDER
  override val description = "Nintendo Switch Homebrew backend using SwitchGDX"
  override val isStandard = false

  override fun createGradleFile(project: Project): GradleFile = SwitchGradleFile(project)

  override fun initiate(project: Project) {
    val name = project.basic.destination.name()
    addGradleTaskDescription(project, "transpile", "transpiles the project into `$id/build/${name}`.")
    addGradleTaskDescription(project, "run", "runs the transpiled application as a desktop program.")
    addGradleTaskDescription(project, "nro", "packages the project into a homebrew NRO located at `$id/build/${name}/${name}.nro`")
    addGradleTaskDescription(project, "deploy", "deploys the NRO to a switch via NxLink.")
    addGradleTaskDescription(project, "ryujinx", "runs the NRO in the Ryujinx emulator.")
    addGradleTaskDescription(project, "uwp", "Generate the UWP project and open Visual Studio")

    val reflectiveBuilder = StringBuilder()
    project.switchReflective.forEach {
      reflectiveBuilder.append("\t\t\"${it}\",\n")
    }

    project.files.add(
      ReplacedContentFile(
        projectName = ID,
        path = path("switch.json"),
        original = path("generator", "switch", "switch.json"),
        replaceMap = HashMap<String, String>().apply {
          put("{MAIN_CLASS}", "${project.basic.rootPackage}.switchgdx.SwitchLauncher")
          put("{REFLECTIVE}", reflectiveBuilder.toString())
        }
      )
    )

    project.files.add(
      CopiedFile(
        projectName = ID,
        path = path("icon.jpg"),
        original = path("generator", "switch", "icon.jpg")
      )
    )
  }
}

/**
 * Represents the Gradle file of the switch project.
 */
class SwitchGradleFile(val project: Project) : GradleFile(Switch.ID) {
  init {
    dependencies.add("project(':${Core.ID}')")
    addDependency("com.thelogicmaster:switch-gdx:\$switchGdxVersion")
    project.properties["switchGdxVersion"] = Repository.JitPack.getLatestVersion("com.thelogicmaster", "switch-gdx").toString()
    project.properties["clearwingVersion"] = Repository.JitPack.getLatestVersion("com.thelogicmaster", "clearwing-vm").toString()
  }

  override fun getContent(): String = """import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    id 'java'
}

def appTitle = '${project.basic.name}'
def appAuthor = ''

configurations {
    transpiler {
        transitive = false
    }
    switchgdx {
        transitive = false
    }
}

dependencies {
    switchgdx "com.thelogicmaster:switch-gdx:${'$'}switchGdxVersion"
    transpiler "com.thelogicmaster.clearwing-vm:transpiler:${'$'}clearwingVersion"

${joinDependencies(dependencies)}}

task transpile(dependsOn: 'build') {
    doLast {
        delete "${'$'}buildDir/dist"

        List libs = []
        configurations.runtimeClasspath.asList().stream()
                .filter({ file -> !file.isDirectory() })
                .forEach({ file -> libs.add(file.absolutePath)} )

        javaexec {
            main = "-jar"
            args = ["${'$'}configurations.transpiler.singleFile.absolutePath", "--input"] + libs + [
                    "${'$'}configurations.switchgdx.singleFile.absolutePath",
                    "${'$'}buildDir/classes",
                    "${'$'}rootDir/core/build/classes",
                    "--output", "${'$'}buildDir/dist",
                    "--config", "${'$'}projectDir/switch.json"
            ]
        }

        copy {
            from { configurations.runtimeClasspath.collect { it.isDirectory() ? it : zipTree(it) } }
            include "*.cpp", "**/*.cpp"
            include "*.hpp", "**/*.hpp"
            include "*.h", "**/*.h"
            include "*.c", "**/*.c"
            into "${'$'}buildDir/dist/src"
            includeEmptyDirs = false
        }

        copy {
            from(configurations.runtimeClasspath.collect { it.isDirectory() ? it : zipTree(it) }) {
                exclude "**/*.class"
                exclude "META-INF", "META-INF/**"
                exclude "**/*.gwt.xml"
                exclude "**.cpp", "**/*.cpp"
                exclude "**.hpp", "**/*.hpp"
                exclude "**.h", "**/*.h"
                exclude "**.c", "**/*.c"
                exclude "switchgdx/**"
            }
            into "${'$'}buildDir/dist/romfs/classpath"
            includeEmptyDirs = false
        }

        copy {
            from "${'$'}rootDir/assets"
            into "${'$'}buildDir/dist/romfs"
        }

        copy {
            from "${'$'}projectDir/icon.jpg"
            into "${'$'}buildDir/dist"
        }

        copy {
            from({ zipTree("${'$'}configurations.switchgdx.singleFile.absolutePath") }) {
                include "switchgdx/project/**"
                eachFile { fcd ->
                    fcd.relativePath = new RelativePath(true, fcd.relativePath.segments.drop(2))
                }
            }
            into "${'$'}buildDir/dist"
        }
        delete "${'$'}buildDir/dist/switchgdx"

        exec {
            if (DefaultNativePlatform.currentOperatingSystem.isWindows())
                commandLine 'cmd', '/c', "C:\\devkitPro\\msys2\\usr\\bin\\rsync -crh --delete --checksum --exclude '/cmake-build-*' --exclude '/build' --exclude '/data' --exclude '/sdmc' --exclude '/build-uwp' --exclude '/.idea' dist/ ${'$'}{rootProject.name}"
            else
                commandLine 'bash', '-c', "rsync -crh --delete --checksum --exclude '/cmake-build-*' --exclude '/build' --exclude '/data' --exclude '/sdmc' --exclude '/.idea' dist/ ${'$'}{rootProject.name}"
            workingDir "${'$'}buildDir"
        }
    }
}
configure(transpile) {
    group "SwitchGDX"
    description = "Run the transpiler to generate the C++ project code"
}

task run(dependsOn: transpile) {
    doLast {
        exec {
            if (DefaultNativePlatform.currentOperatingSystem.isWindows())
                commandLine 'C:\\devkitPro\\msys2\\msys2_shell.cmd', '-mingw64', '-where', "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}", '-c', 'cmake -DCMAKE_BUILD_TYPE=Debug -S . -B build-run -G \'MSYS Makefiles\' && cmake --build build-run || sleep 50000'
            else
                commandLine 'bash', '-c', 'cmake -DCMAKE_BUILD_TYPE=Debug -S . -B cmake-build-run -G Ninja && cmake --build cmake-build-run'
            workingDir "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}"
        }
        if (DefaultNativePlatform.currentOperatingSystem.isWindows()) {
            copy { // Todo: Copy only needed
                from "C:\\devkitPro\\msys2\\mingw64\\bin"
                include "*.dll"
                into "${'$'}buildDir\\${'$'}{rootProject.name}\\cmake-build-run"
            }
        }
        exec {
            if (DefaultNativePlatform.currentOperatingSystem.isWindows())
                commandLine 'cmd', '/c', 'cmake-build-run\\SwitchGDX.exe'
            else
                commandLine 'bash', '-c', './cmake-build-run/SwitchGDX'
            workingDir "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}"
        }
    }
}
configure(run) {
    group "SwitchGDX"
    description = "Run with the SwitchGDX backend on desktop"
}

task nro(dependsOn: transpile) {
    doLast {
        exec {
            if (DefaultNativePlatform.currentOperatingSystem.isWindows())
                commandLine 'C:\\devkitPro\\msys2\\msys2_shell.cmd', '-mingw64', '-where', "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}", '-c',
                        'cmake --toolchain DevkitA64Libnx.cmake -B cmake-build-switch . && cmake --build cmake-build-switch -j8 || sleep 50000'
            else
                commandLine 'bash', '-c', 'cmake --toolchain DevkitA64Libnx.cmake -B cmake-build-switch . && cmake --build cmake-build-switch -j8'
            workingDir "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}"
            environment 'APP_TITLE', appTitle
            environment 'APP_AUTHOR', appAuthor
            environment 'APP_VERSION', version
        }
    }
}
configure(nro) {
    group "SwitchGDX"
    description = "Build a homebrew NRO"
}

task deploy(dependsOn: nro) {
    doLast {
        exec {
            if (DefaultNativePlatform.currentOperatingSystem.isWindows())
                commandLine 'C:\\devkitPro\\msys2\\usr\\bin\\bash', '-c', "/opt/devkitpro/tools/bin/nxlink --server ./cmake-build-switch/${'$'}{rootProject.name}.nro"
            else
                commandLine 'bash', '-c', "\${'$'}DEVKITPRO/tools/bin/nxlink --server ./cmake-build-switch/${'$'}{rootProject.name}.nro"
            workingDir "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}"
        }
    }
}
configure(deploy) {
    group "SwitchGDX"
    description = "Run with the SwitchGDX backend on Switch via NxLink"
}

task ryujinx(dependsOn: nro) {
    doLast {
        Properties properties = new Properties();
        try {
            properties.load(project.rootProject.file('local.properties').newDataInputStream())
        } catch (FileNotFoundException ignored) {
            throw new Exception('The path to the Ryujinx emulator executable is unset. Set "ryujinxPath" in local.properties file.')
        }
        if (!properties.containsKey('ryujinxPath'))
            throw new Exception('The path to the Ryujinx emulator executable is unset. Set "ryujinxPath" in local.properties file.')
        exec {
            if (DefaultNativePlatform.currentOperatingSystem.isWindows())
                commandLine 'cmd', '/c', "${'$'}{properties.getProperty("ryujinxPath")} cmake-build-switch/${'$'}{rootProject.name}.nro"
            else
                commandLine 'bash', '-c', "${'$'}{properties.getProperty("ryujinxPath")} ./cmake-build-switch/${'$'}{rootProject.name}.nro"
            workingDir "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}"
        }
    }
}
configure(ryujinx) {
    group "SwitchGDX"
    description = "Run with the SwitchGDX backend on Switch via NxLink"
}

task uwp(dependsOn: transpile) {
    doLast {
        exec {
            commandLine 'cmd', '/c', "call uwp.cmd"
            workingDir "${'$'}buildDir${'$'}{File.separator}${'$'}{rootProject.name}"
        }
    }
}
configure(uwp) {
    group "SwitchGDX"
    description = "Generate the UWP project and open Visual Studio"
}
"""
}
