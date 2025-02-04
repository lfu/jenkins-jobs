void git_clone_foreman_infra(args = [:]) {
    target_dir = args.target_dir ?: ''

    dir(target_dir) {
        git url: 'https://github.com/theforeman/foreman-infra', poll: false
    }
}

void git_clone_jenkins_jobs(args = [:]) {
    target_dir = args.target_dir ?: ''

    dir(target_dir) {
        git url: 'https://github.com/theforeman/jenkins-jobs', poll: false
    }
}

def list_files(glob = '') {
    sh(script: "ls -1 ${glob}", returnStdout: true, label: "list files: '${glob}'").trim().split()
}

def set_job_build_description(job_name, status, file_name) {
    def build_description = ""

    build_description += "<a href=\"${env.BUILD_URL}/artifact/artifacts/${job_name}/\">${job_name}</a> (${status})"
    if (fileExists(file_name)) {
       link = readFile(file_name)
       build_description += " (<a href=\"${link}\">remote job</a>)"
    }
    build_description += "<br/>"

    if (currentBuild.description == null) {
        currentBuild.description = ''
    }

    currentBuild.description += build_description
}

def runIndividualCicoJob(job_name, number = 0, job_parameters = null, job_extra_vars = null) {
    def status = 'unknown'
    def link_file_name = "${env.WORKSPACE}/jobs/${job_name}-${number}"
    def extra_vars = [
        "jenkins_job_name": "${job_name}",
        "jenkins_job_link_file": link_file_name
    ]
    if (job_parameters) {
        extra_vars["jenkins_job_parameters"] = job_parameters
    }
    if (job_extra_vars) {
        extra_vars += job_extra_vars
    }

    sleep(number * 5) //See https://bugs.centos.org/view.php?id=14920
    try {
        withCredentials([usernamePassword(credentialsId: 'centos-jenkins-openshift', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
            runPlaybook(
                playbook: 'centos.org/ansible/jenkins_job.yml',
                extraVars: extra_vars,
                sensitiveExtraVars: ["jenkins_username": "${env.USERNAME}", "jenkins_password": "${env.PASSWORD}"]
            )
        }
        status = 'passed'
    } catch(Exception ex) {
        status = 'failed'
        throw ex
    } finally {
        script {
            if (extra_vars['jenkins_artifacts_directory']) {
                def relative_artifacts_directory = extra_vars['jenkins_artifacts_directory'].replace("${env.WORKSPACE}/", '')
                archiveArtifacts artifacts: "${relative_artifacts_directory}/**", allowEmptyArchive: true
                step([$class: "TapPublisher", testResults: "${relative_artifacts_directory}/**/*.tap", failIfNoResults: false])
            }
            set_job_build_description(job_name, status, link_file_name)
        }
    }
}

def runCicoJob(job_name, job_parameters = null, job_extra_vars = null) {
    script {
        git_clone_jenkins_jobs()
        try {
            runIndividualCicoJob(job_name, 0, job_parameters, job_extra_vars)
        } finally {
            deleteDir()
        }
    }
}

def runCicoJobsInParallel(jobs) {
    def branches = [:]
    for (int i = 0; i < jobs.size(); i++) {
        def index = i // fresh variable per iteration; i will be mutated
        branches[jobs[index]['name']] = {
            runIndividualCicoJob(jobs[index]['job'], index, jobs[index]['parameters'], jobs[index]['extra_vars'])
        }
    }

    script {
        git_clone_jenkins_jobs()
        try {
            parallel branches
        } finally {
            deleteDir()
        }
    }
}

def runCicoPipelines(project, version, config, expected_version = '') {
    def pipes = []

    config.each { type, operating_systems ->
        pipes += operating_systems.collect { os ->
            [
                name: "${os}-${type}",
                job: "foreman-pipeline-${project}-${version}-${os}-${type}",
                parameters: [
                    expected_version: expected_version
                ],
                extra_vars: [
                    jenkins_download_artifacts: 'true',
                    jenkins_artifacts_directory: "${env.WORKSPACE}/artifacts/foreman-pipeline-${project}-${version}-${os}-${type}/",
                ]
            ]
        }
    }

    runCicoJobsInParallel(pipes)
}

def runDuffyPipeline(project, version, expected_version = '') {
    def job = "foreman-pipeline-${project}-${version}"
    def parameters = [
            expected_version: expected_version
        ]
    def extra_vars = [
            jenkins_download_artifacts: 'true',
            jenkins_artifacts_directory: "${env.WORKSPACE}/artifacts/foreman-pipeline-${project}-${version}/",
        ]

    runCicoJob(job, parameters, extra_vars)
}

def runDuffyPipelines(projects, version, expected_version = '') {
    def pipes = []

    pipes += projects.collect { project ->
        [
            name: "${project}-${version}",
            job: "foreman-pipeline-${project}-${version}",
            parameters: [
                expected_version: expected_version
            ],
            extra_vars: [
                jenkins_download_artifacts: 'true',
                jenkins_artifacts_directory: "${env.WORKSPACE}/artifacts/foreman-pipeline-${project}-${version}/",
            ]
        ]
    }

    runCicoJobsInParallel(pipes)
}

def notifyDiscourse(env, introText, description) {
    emailext(
        subject: "${env.JOB_NAME} ${env.BUILD_ID} failed",
        to: 'ci@community.theforeman.org',
        body: [introText, env.BUILD_URL, description].minus(null).join('\n\n')
    )
}
