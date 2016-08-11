// Groovy Script: http://www.groovy-lang.org/syntax.html
// Jenkins DSL: https://github.com/jenkinsci/job-dsl-plugin/wiki

import jobs.generation.*;

// The input project name (e.g. dotnet/corefx)
def projectName = GithubProject
// The input branch name (e.g. master)
def branchName = GithubBranchName
def defaultBranch = "*/${branchName}"

def isPr = false;

def jobName = Utilities.getFullJobName(projectName, "perf_run", isPr)
def myJob = job(jobName) {
    description('perf run')

    steps {
        powerShell("""
            set-variable -name LastExitCode 0
            set-strictmode -version 2.0
            \$ErrorActionPreference="Stop"

            Invoke-WebRequest -Uri http://dotnetci.blob.core.windows.net/roslyn-perf/cpc.zip -OutFile cpc.zip
            [Reflection.Assembly]::LoadWithPartialName('System.IO.Compression.FileSystem') | Out-Null
            If (Test-Path C:/CPC) {
                Remove-Item -Recurse -Force C:/CPC
            }
            [IO.Compression.ZipFile]::ExtractToDirectory('cpc.zip', 'C:/CPC/')

            # Preview 4 specific
            [Environment]::SetEnvironmentVariable("VS150COMNTOOLS", "C:\\Program Files (x86)\\Microsoft Visual Studio\\VS15Preview\\Common7\\Tools", "Process")

            ./cibuild.cmd /testPerfRun
            """)
    }

    publishers {
        postBuildScripts {
            steps {
                powerShell("""
                    set-variable -name LastExitCode 0
                    set-strictmode -version 2.0
                    \$ErrorActionPreference="Stop"

                    # If the test runner crashes and doesn't shut down CPC, CPC could fill
                    # the entire disk with ETL traces.
                    try {
                        taskkill /F /IM CPC.exe 2>&1 | Out-Null
                    }
                    catch {}

                    # Move all etl files to the a folder for archiving
                    echo "creating ToArchive directory"
                    mkdir ToArchive
                    echo "moving /CPC/DataBackup* to ToArchive"
                    mv /CPC/DataBackup* ToArchive
                    ls ToArchive

                    # Clean CPC out of the machine
                    If (Test-Path C:/CPC) {
                        echo "removing C:/CPC ..."
                        Remove-Item -Recurse -Force C:/CPC
                        echo "done."
                    }

                    If (Test-Path C:/CPCTraces) {
                        echo "removing C:/CPCTraces"
                        Remove-Item -Recurse -Force C:/CPCTraces
                        echo "done."
                    }
                    If (Test-Path C:/PerfLogs) {
                        echo "removing C:/PerfLogs"
                        Remove-Item -Recurse -Force C:/PerfLogs
                        echo "done."
                    }
                    If (Test-Path C:/PerfTemp) {
                        echo "removing C:/PerfTemp"
                        Remove-Item -Recurse -Force C:/PerfTemp
                        echo "done."
                    }
                    exit 0
                    """)
            }
        }
    }
}

def archiveSettings = new ArchivalSettings()
archiveSettings.addFiles('ToArchive/**/*.*')
Utilities.addArchival(myJob, archiveSettings)
Utilities.standardJobSetup(myJob, projectName, isPr, defaultBranch)
Utilities.setMachineAffinity(myJob, 'Windows_NT', 'latest-or-auto-perf')
Utilities.addGithubPushTrigger(myJob)
