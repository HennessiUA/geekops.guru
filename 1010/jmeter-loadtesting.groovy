def rdsSnapshot(){
    snapshot = sh (returnStdout: true, script:"aws rds describe-db-cluster-snapshots --query 'DBClusterSnapshots[].DBClusterSnapshotIdentifier[]' | grep YOUR_SNAPSHOT_NAME | cut -b 6-44 | tr -d '\n'")
}

def getPod(){
    jmMaster = sh (returnStdout: true, script:"kubectl -n YOUR_NAMESPACE get pods | grep jmeter-master | awk {'print \$1'} | tr -d '\n'")
}

def startTime(){
    start = sh (returnStdout: true, script:"echo `date +%Y%m%dT%H%M%S` | tr -d '\n'")
}

def endTime(){
    end = sh (returnStdout: true, script:"echo `date +%Y%m%dT%H%M%S` | tr -d '\n'")
}

node('master'){

    logFileFilter {

        docker.image('DOCKER_IMAGE').inside("-v /var/run/docker.sock:/var/run/docker.sock -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_ACCESS_KEY_ID} -e AWS_DEFAULT_REGION=us-east-2"){

            if (env.CREATE_RDS.toBoolean()){

                stage("RDS: CreateLoadTestDB"){
                    rdsSnapshot()
                    echo "============================== Restore DB Cluster  ==================================="
                    sh "aws rds restore-db-cluster-from-snapshot \
                            --snapshot-identifier ${snapshot} \
                            --engine aurora-mysql \
                            --engine-version 5.7.mysql_aurora.2.07.2 \
                            --vpc-security-group-ids YOUR_SECURITY_GROUP_ID \
                            --db-cluster-identifier RDS_CLUSTER_NAME \
                            --db-subnet-group-name YOUR_SUBNET_GROUP_NAME \
                            --db-cluster-parameter-group-name YOUR_PARAMETER_GROUP_NAME"
                    echo "================================ Create DB writer ====================================="
                    sh "aws rds create-db-instance \
                            --engine aurora-mysql \
                            --publicly-accessible \
                            --db-cluster-identifier RDS_CLUSTER_NAME \
                            --db-instance-identifier RDS_INSTANCE-1_NAME \
                            --db-instance-class db.r5.large"
                    echo "================================ Create DB reader ====================================="
                    sh "aws rds create-db-instance \
                            --publicly-accessible \
                            --engine aurora-mysql \
                            --db-cluster-identifier RDS_CLUSTER_NAME \
                            --db-instance-identifier RDS_INSTANCE-2_NAME \
                            --db-instance-class db.r5.large"
                    echo "==========================  Wait when DB have been created  ==========================="
                    sh "aws rds wait db-instance-available --db-instance-identifier RDS_INSTANCE-1_NAME"
                    sh "aws rds wait db-instance-available --db-instance-identifier RDS_INSTANCE-2_NAME"
                }
                stage("Route53: Create Record"){
                    sh"""aws route53 change-resource-record-sets --hosted-zone-id HOSTERZONE_ID --change-batch '{ "Comment": "YOUR_COMMENT", "Changes": [ { "Action": "UPSERT", "ResourceRecordSet": { "Name": "YOUR_NAME_RDS_WRITER", "Type": "CNAME", "TTL": 300, "ResourceRecords": [ { "Value": "RDS_CLUSTER_ENDPOINT_WRITER" } ] } } ] }'"""
                    sh"""aws route53 change-resource-record-sets --hosted-zone-id HOSTERZONE_ID --change-batch '{ "Comment": "YOUR_COMMENT", "Changes": [ { "Action": "UPSERT", "ResourceRecordSet": { "Name": "YOUR_RDS_READER", "Type": "CNAME", "TTL": 300, "ResourceRecords": [ { "Value": "RDS_CLUSTER_ENDPOINT_READER" } ] } } ] }'"""
                }

            }

            stage('Clone Repository'){

                gitenv = git branch: "${QA_REPO_BRANCH}",
                        credentialsId: "YOUR_CREDENTIALS_FOR_ACCESS_TO_GItHub",
                        url: "${QA_REPO_URL}"
                GIT_COMMIT_SHORT = gitenv.GIT_COMMIT.take(8)
                sh "aws eks update-kubeconfig --region us-east-2 --name ${AWS_EKS_CLUSTER}"
                sh "kubectl cluster-info"
            }

            stage("Copy FileJMX to Jmeter-Master"){

                getPod()
                sh "sops --config .sops.yaml -d -i ${JMETER_TESTPLAN_FILE}" // decrypt testplan file
                sh "kubectl -n ${AWS_EKS_NAMESPACE} cp ${JMETER_TESTPLAN_FILE} ${AWS_EKS_NAMESPACE}/${jmMaster}:/test/"
            }

            stage("Scale Up Jmeter-Slaves"){

                sh "kubectl -n ${AWS_EKS_NAMESPACE} scale deployment  jmeter-slave --replicas=${JMETER_SLAVES_COUNT}"
                sh "kubectl -n ${AWS_EKS_NAMESPACE} wait --all pods --for=condition=Ready --timeout=10m"
            }

            stage("Run LoadTesting"){
                startTime()
                sh "kubectl exec -i -n ${AWS_EKS_NAMESPACE} ${jmMaster} -- sh -c 'ONE_SHOT=true; /run-test.sh'"
                // delete TestPlanFile from Pod
                sh "kubectl -n ${AWS_EKS_NAMESPACE} exec ${jmMaster} -- rm -rf /test/${JMETER_TESTPLAN_FILE}"
                // stop LoadTest on all jmeter-slaves
                sh "kubectl -n ${AWS_EKS_NAMESPACE} exec ${jmMaster} -- sh -c '/opt/jmeter/bin/shutdown.sh'"
            }

            stage("Scale Down Jmeter-Slaves"){
                endTime()
                sh "kubectl -n ${AWS_EKS_NAMESPACE} scale deployment  jmeter-slave --replicas=1"
                sh "kubectl -n ${AWS_EKS_NAMESPACE} wait deployment/jmeter-slave --for=condition=available"
            }

            stage("ShowTestResult"){
                
                echo "https://URL_TO_GRAFANA/d/PIQCKqO7k/apache-jmeter-dashboard-using-core-influxdbbackendlistenerclient?orgId=1&from=${start}&to=${end}&var-data_source=jmeter-influxdb&var-application=Test_jmeter&var-transaction=GET%20challenges&var-measurement_name=jmeter&var-send_interval=5"
            
            }
            if (env.DELETE_RDS.toBoolean()){

                stage("Delete RDS Cluster"){

                    echo "==========================  AWS RDS: delete RDS Instance  ==========================="
                    sh "aws rds delete-db-instance --skip-final-snapshot --db-instance-identifier RDS_INSTANCE-1_NAME"
                    sh "aws rds delete-db-instance --skip-final-snapshot --db-instance-identifier RDS_INSTANCE-2_NAME"

                    echo "===================== AWS RDS: Wait when DB have been deleted ======================"
                    sh "aws rds wait db-instance-deleted --db-instance-identifier RDS_INSTANCE-1_NAME"
                    sh "aws rds wait db-instance-deleted --db-instance-identifier RDS_INSTANCE-2_NAME"

                    echo "========================  AWS RDS: delete RDS Cluster  ========================="
                    sh "aws rds delete-db-cluster --db-cluster-identifier RDS_CLUSTER_NAME --skip-final-snapshot"
                }

            }
        }
    }
}