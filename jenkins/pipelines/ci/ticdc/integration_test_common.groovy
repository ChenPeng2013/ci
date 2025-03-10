/**
 * The total number of integration test groups.
 */
TOTAL_COUNT = 0

/**
 * Integration testing number of tests per group.
 */
GROUP_SIZE = 2

/**
 * Partition the array.
 * @param array
 * @param size
 * @return Array partitions.
 */
static def partition(array, size) {
    def partitions = []
    int partitionCount = array.size() / size

    partitionCount.times { partitionNumber ->
        int start = partitionNumber * size
        int end = start + size - 1
        partitions << array[start..end]
    }

    if (array.size() % size) partitions << array[partitionCount * size..-1]
    return partitions
}

/**
 * Prepare the binary file for testing.
 */
def prepare_binaries() {
    stage('Prepare Binaries') {
        def prepares = [:]

        prepares["build binaries"] = {
            node("${GO_TEST_SLAVE}") {
                container("golang") {
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    def ws = pwd()
                    deleteDir()
                    unstash 'ticdc'

                    dir("go/src/github.com/pingcap/ticdc") {
                        sh """
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make cdc
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_build
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make kafka_consumer
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make check_failpoint_ctl
                            tar czvf ticdc_bin.tar.gz bin/*
                            curl -F test/cdc/ci/ticdc_bin_${env.BUILD_NUMBER}.tar.gz=@ticdc_bin.tar.gz http://fileserver.pingcap.net/upload
                        """
                    }
                    dir("go/src/github.com/pingcap/ticdc/tests") {
                        def cases_name = sh(
                                script: 'find . -maxdepth 2 -mindepth 2 -name \'run.sh\' | awk -F/ \'{print $2}\'',
                                returnStdout: true
                        ).trim().split().join(" ")
                        sh "echo ${cases_name} > CASES"
                    }
                    stash includes: "go/src/github.com/pingcap/ticdc/tests/CASES", name: "cases_name", useDefaultExcludes: false
                }
            }
        }

        parallel prepares
    }
}

/**
 * Start running tests.
 * @param sink_type Type of Sink, optional value: mysql/kafaka.
 * @param node_label
 */
def tests(sink_type, node_label) {
    stage("Tests") {
        def test_cases = [:]
        // Set to fail fast.
        test_cases.failFast = true

        // Start running unit tests.
        test_cases["unit test"] = {
            node(node_label) {
                container("golang") {
                    def ws = pwd()
                    deleteDir()
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"
                    unstash 'ticdc'

                    dir("go/src/github.com/pingcap/ticdc") {
                        sh """
                            rm -rf /tmp/tidb_cdc_test
                            mkdir -p /tmp/tidb_cdc_test
                            GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make test
                            rm -rf cov_dir
                            mkdir -p cov_dir
                            ls /tmp/tidb_cdc_test
                            cp /tmp/tidb_cdc_test/cov*out cov_dir
                        """
                        sh """
                        tail /tmp/tidb_cdc_test/cov*
                        """
                    }
                    stash includes: "go/src/github.com/pingcap/ticdc/cov_dir/**", name: "unit_test", useDefaultExcludes: false
                }
            }
        }

        // Start running integration tests.
        def run_integration_test = { step_name, case_names ->
            node(node_label) {
                container("golang") {
                    def ws = pwd()
                    deleteDir()
                    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
                    println "work space path:\n${ws}"
                    println "this step will run tests: ${case_names}"
                    unstash 'ticdc'
                    dir("go/src/github.com/pingcap/ticdc") {
                        download_binaries()
                        try {
                            sh """
                                sudo pip install s3cmd
                                rm -rf /tmp/tidb_cdc_test
                                mkdir -p /tmp/tidb_cdc_test
                                echo "${env.KAFKA_VERSION}" > /tmp/tidb_cdc_test/KAFKA_VERSION
                                GOPATH=\$GOPATH:${ws}/go PATH=\$GOPATH/bin:${ws}/go/bin:\$PATH make integration_test_${sink_type} CASE="${case_names}"
                                rm -rf cov_dir
                                mkdir -p cov_dir
                                ls /tmp/tidb_cdc_test
                                cp /tmp/tidb_cdc_test/cov*out cov_dir || touch cov_dir/dummy_file_${step_name}
                            """
                            // cyclic tests do not run on kafka sink, so there is no cov* file.
                            sh """
                            tail /tmp/tidb_cdc_test/cov* || true
                            """
                        } catch (Exception e) {
                            sh """
                                echo "archive all log"
                                for log in `ls /tmp/tidb_cdc_test/*/*.log`; do
                                    dirname=`dirname \$log`
                                    basename=`basename \$log`
                                    mkdir -p "log\$dirname"
                                    tar zcvf "log\${log}.tgz" -C "\$dirname" "\$basename"
                                done
                            """
                            archiveArtifacts artifacts: "log/tmp/tidb_cdc_test/**/*.tgz", caseSensitive: false
                            throw e;
                        }
                    }
                    stash includes: "go/src/github.com/pingcap/ticdc/cov_dir/**", name: "integration_test_${step_name}", useDefaultExcludes: false
                }
            }
        }


        // Gets the name of each case.
        unstash 'cases_name'
        def cases_name = sh(
                script: 'cat go/src/github.com/pingcap/ticdc/tests/CASES',
                returnStdout: true
        ).trim().split()

        // Run integration tests in groups.
        def step_cases = []
        def cases_namesList = partition(cases_name, GROUP_SIZE)
        TOTAL_COUNT = cases_namesList.size()
        cases_namesList.each { case_names ->
            step_cases.add(case_names)
        }
        step_cases.eachWithIndex { case_names, index ->
            def step_name = "step_${index}"
            test_cases["integration test ${step_name}"] = {
                run_integration_test(step_name, case_names.join(" "))
            }
        }

        parallel test_cases
    }
}

/**
 * Download the integration test-related binaries.
 */
def download_binaries() {
    def TIDB_BRANCH = params.getOrDefault("release_test__tidb_commit", "master")
    def TIKV_BRANCH = params.getOrDefault("release_test__tikv_commit", "master")
    def PD_BRANCH = params.getOrDefault("release_test__pd_commit", "master")
    def TIFLASH_BRANCH = params.getOrDefault("release_test__release_branch", "master")
    def TIFLASH_COMMIT = params.getOrDefault("release_test__tiflash_commit", null)

    def mBranch = ghprbTargetBranch =~ /^release-4.0/
    if (mBranch) {
        TIDB_BRANCH = params.getOrDefault("release_test__tidb_commit", "release-4.0")
        TIKV_BRANCH = params.getOrDefault("release_test__tikv_commit", "release-4.0")
        PD_BRANCH = params.getOrDefault("release_test__pd_commit", "release-4.0")
        TIFLASH_BRANCH = params.getOrDefault("release_test__release_branch", "release-4.0")
    }
    mBranch = null
    println "ghprbTargetBranch=${ghprbTargetBranch}"
    println "TIDB_BRANCH=${TIDB_BRANCH}"
    println "PD_BRANCH=${PD_BRANCH}"
    println "TIFLASH_BRANCH=${TIFLASH_BRANCH}"


    // parse tidb branch
    def m1 = ghprbCommentBody =~ /tidb\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m1) {
        TIDB_BRANCH = "${m1[0][1]}"
    }
    m1 = null
    println "TIDB_BRANCH=${TIDB_BRANCH}"

    // parse tikv branch
    def m2 = ghprbCommentBody =~ /tikv\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m2) {
        TIKV_BRANCH = "${m2[0][1]}"
    }
    m2 = null
    println "TIKV_BRANCH=${TIKV_BRANCH}"

    // parse pd branch
    def m3 = ghprbCommentBody =~ /pd\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m3) {
        PD_BRANCH = "${m3[0][1]}"
    }
    m3 = null
    println "PD_BRANCH=${PD_BRANCH}"

    // parse tiflash branch
    def m4 = ghprbCommentBody =~ /tiflash\s*=\s*([^\s\\]+)(\s|\\|$)/
    if (m4) {
        TIFLASH_BRANCH = "${m4[0][1]}"
    }
    m4 = null
    println "TIFLASH_BRANCH=${TIFLASH_BRANCH}"

    println "debug command:\nkubectl -n jenkins-ci exec -ti ${NODE_NAME} bash"
    def tidb_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tidb/${TIDB_BRANCH}/sha1").trim()
    def tikv_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tikv/${TIKV_BRANCH}/sha1").trim()
    def pd_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/pd/${PD_BRANCH}/sha1").trim()
    def tiflash_sha1 = TIFLASH_COMMIT
    if (TIFLASH_COMMIT == null) {
        tiflash_sha1 = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/refs/pingcap/tiflash/${TIFLASH_BRANCH}/sha1").trim()
    }
    sh """
        mkdir -p third_bin
        mkdir -p tmp
        mkdir -p bin

        tidb_url="${FILE_SERVER_URL}/download/builds/pingcap/tidb/${tidb_sha1}/centos7/tidb-server.tar.gz"
        tikv_url="${FILE_SERVER_URL}/download/builds/pingcap/tikv/${tikv_sha1}/centos7/tikv-server.tar.gz"
        pd_url="${FILE_SERVER_URL}/download/builds/pingcap/pd/${pd_sha1}/centos7/pd-server.tar.gz"
        tiflash_url="${FILE_SERVER_URL}/download/builds/pingcap/tiflash/${TIFLASH_BRANCH}/${tiflash_sha1}/centos7/tiflash.tar.gz"
        minio_url="${FILE_SERVER_URL}/download/minio.tar.gz"

        curl \${tidb_url} | tar xz -C ./tmp bin/tidb-server
        curl \${pd_url} | tar xz -C ./tmp bin/*
        curl \${tikv_url} | tar xz -C ./tmp bin/tikv-server
        curl \${minio_url} | tar xz -C ./tmp/bin minio
        mv tmp/bin/* third_bin
        curl \${tiflash_url} | tar xz -C third_bin
        mv third_bin/tiflash third_bin/_tiflash
        mv third_bin/_tiflash/* third_bin
        curl ${FILE_SERVER_URL}/download/builds/pingcap/go-ycsb/test-br/go-ycsb -o third_bin/go-ycsb
        curl -L http://fileserver.pingcap.net/download/builds/pingcap/cdc/etcd-v3.4.7-linux-amd64.tar.gz | tar xz -C ./tmp
        mv tmp/etcd-v3.4.7-linux-amd64/etcdctl third_bin
        curl http://fileserver.pingcap.net/download/builds/pingcap/cdc/sync_diff_inspector.tar.gz | tar xz -C ./third_bin
        curl -L https://github.com/stedolan/jq/releases/download/jq-1.6/jq-linux64 -o jq
        mv jq third_bin
        chmod a+x third_bin/*
        rm -rf tmp
        curl -L http://fileserver.pingcap.net/download/test/cdc/ci/ticdc_bin_${env.BUILD_NUMBER}.tar.gz | tar xvz -C .
        mv ./third_bin/* ./bin
        rm -rf third_bin
    """
}

/**
 * Collect and calculate test coverage.
 */
def coverage() {
    stage('Coverage') {
        node("${GO_TEST_SLAVE}") {
            def ws = pwd()
            deleteDir()
            unstash 'ticdc'
            unstash 'unit_test'

            // unstash all integration tests.
            def step_names = []
            for ( int i = 1; i < TOTAL_COUNT; i++ ) {
                step_names.add("integration_test_step_${i}")
            }
            step_names.each { item ->
                unstash item
            }

            dir("go/src/github.com/pingcap/ticdc") {
                container("golang") {
                    archiveArtifacts artifacts: 'cov_dir/*', fingerprint: true

                    timeout(30) {
                        sh """
                        rm -rf /tmp/tidb_cdc_test
                        mkdir -p /tmp/tidb_cdc_test
                        cp cov_dir/* /tmp/tidb_cdc_test
                        set +x
                        BUILD_NUMBER=${env.BUILD_NUMBER} CODECOV_TOKEN="${CODECOV_TOKEN}" COVERALLS_TOKEN="${COVERALLS_TOKEN}" GOPATH=${ws}/go:\$GOPATH PATH=${ws}/go/bin:/go/bin:\$PATH JenkinsCI=1 make coverage
                        set -x
                        """
                    }
                }
            }
        }
    }
}

return this
