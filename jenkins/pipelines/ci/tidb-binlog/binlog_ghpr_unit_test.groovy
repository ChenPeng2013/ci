@NonCPS
boolean isMoreRecentOrEqual( String a, String b ) {
    if (a == b) {
        return true
    }

    [a,b]*.tokenize('.')*.collect { it as int }.with { u, v ->
       Integer result = [u,v].transpose().findResult{ x,y -> x <=> y ?: null } ?: u.size() <=> v.size()
       return (result == 1)
    } 
}

string trimPrefix = {
        it.startsWith('release-') ? it.minus('release-').split("-")[0] : it 
    }

def boolean isBranchMatched(List<String> branches, String targetBranch) {
    for (String item : branches) {
        if (targetBranch.startsWith(item)) {
            println "targetBranch=${targetBranch} matched in ${branches}"
            return true
        }
    }
    return false
}

def isNeedGo1160 = false
releaseBranchUseGo1160 = "release-5.1"

if (!isNeedGo1160) {
    isNeedGo1160 = isBranchMatched(["master", "hz-poc"], ghprbTargetBranch)
}
if (!isNeedGo1160 && ghprbTargetBranch.startsWith("release-")) {
    isNeedGo1160 = isMoreRecentOrEqual(trimPrefix(ghprbTargetBranch), trimPrefix(releaseBranchUseGo1160))
    if (isNeedGo1160) {
        println "targetBranch=${ghprbTargetBranch}  >= ${releaseBranchUseGo1160}"
    }
}
if (isNeedGo1160) {
    println "This build use go1.16"
    GO_BUILD_SLAVE = GO1160_BUILD_SLAVE
    GO_TEST_SLAVE = GO1160_TEST_SLAVE
} else {
    println "This build use go1.13"
}
println "BUILD_NODE_NAME=${GO_BUILD_SLAVE}"
println "TEST_NODE_NAME=${GO_TEST_SLAVE}"

node("${GO_TEST_SLAVE}") {
    def ws = pwd()
    deleteDir()
  
    dir("${ws}/go/src/github.com/pingcap/tidb-binlog") {
        stage("Prepare"){
            container("golang") {
                println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash" 

                if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                    deleteDir()
                }
                try {
                    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                } catch (error) {
                    retry(2) {
                        echo "checkout failed, retry.."
                        sleep 5
                        if (sh(returnStatus: true, script: '[ -d .git ] && [ -f Makefile ] && git rev-parse --git-dir > /dev/null 2>&1') != 0) {
                            deleteDir()
                        }
                        checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: '+refs/pull/*:refs/remotes/origin/pr/*', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                    }
                }

                sh "git checkout -f ${ghprbActualCommit}"
                // if (ghprbTargetBranch == "master"){
                //   echo "Target branch: ${ghprbTargetBranch}, load script from pr..."
                //   checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'PruneStaleBranch'], [$class: 'CleanBeforeCheckout']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', refspec: 'pull/${ghprbPullId}/head', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                //   // 等到大多数 PR update  master 后可以取消下面的注释
                //   // sh"""
                //   // git checkout -f ${ghprbActualCommit}
                //   // """
                // }else{
                //   echo "Target branch: ${ghprbTargetBranch}, load script from master..."
                //   checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: 'master']], doGenerateSubmoduleConfigurations: false, userRemoteConfigs: [[credentialsId: 'github-sre-bot-ssh', url: 'git@github.com:pingcap/tidb-binlog.git']]]
                // }
            }
        }
    }
    
    catchError {
        stage("unit test") {
            container("golang") {
                dir("go/src/github.com/pingcap/tidb-binlog") {
                    sh """
                        GO111MODULE=off GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make test
                    """
                }
            }   
        }
        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        def duration = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[#${ghprbPullId}: ${ghprbPullTitle}]" + "\n" +
                "${ghprbPullLink}" + "\n" +
                "${ghprbPullDescription}" + "\n" +
                "Unit Test Result: `${currentBuild.result}`" + "\n" +
                "Elapsed Time: `${duration} mins` " + "\n" +
                "${env.RUN_DISPLAY_URL}"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#jenkins-ci', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
        }
    }
}





