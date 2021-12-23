def rdsSnapshot(){
    // sort and filter rds snapshot for last day
    aurora_snap = sh (returnStdout: true, script:"aws rds describe-db-cluster-snapshots --query 'DBClusterSnapshots[].DBClusterSnapshotIdentifier[]' | grep rds:${RDS_TO_RESTORE} | grep ${RESTORE_DATE} |cut -d '\"' -f2 | tr -d '\n'")
    rds_snap = sh (returnStdout: true, script:"aws rds describe-db-snapshots --query 'DBSnapshots[].DBSnapshotIdentifier[]' | grep ${RDS_TO_RESTORE} | grep ${RESTORE_DATE} | cut -d '\"' -f2 | tr -d '\n'")
}

node('master'){

    docker.image('amazon/aws-cli:2.2.33').inside("-v /var/run/docker.sock:/var/run/docker.sock -e AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID} -e AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY} -e AWS_DEFAULT_REGION=${AWS_REGION} --entrypoint=''") {
        
        rdsSnapshot()
        
        switch("${CHOSE_ACTION}"){
            
            case "restore-aurora-cluster":
                stage("RDS: Restore Aurora Cluster") {
                    echo "===================================  AWS RDS: Restore Aurora Cluster  ========================================"
                    sh "aws rds restore-db-cluster-from-snapshot \
                            --snapshot-identifier ${aurora_snap} \
                            --engine ${RDS_ENGINE} \
                            --engine-version ${RDS_ENGINE_VERSION} \
                            --vpc-security-group-ids ${RDS_VPC_SG_ID} \
                            --db-cluster-identifier restored-${RDS_TO_RESTORE} \
                            --db-subnet-group-name ${RDS_SUBNET_GROUP_NAME} \
                            --db-cluster-parameter-group-name ${RDS_PARAMETER_GROUP_NAME}"

                    echo "==================================  AWS RDS: Create Aurora instance-1  ======================================="
                    sh "aws rds create-db-instance \
                            --engine ${RDS_ENGINE} \
                            --publicly-accessible \
                            --db-cluster-identifier restored-${RDS_TO_RESTORE} \
                            --db-instance-identifier restored-${RDS_TO_RESTORE}-instance-1 \
                            --db-instance-class ${RDS_INSTANCE_TYPE}"

                    echo "=================================  AWS RDS: Create Aurora instance-2  ======================================="
                    sh "aws rds create-db-instance \
                            --publicly-accessible \
                            --engine ${RDS_ENGINE} \
                            --db-cluster-identifier restored-${RDS_TO_RESTORE} \
                            --db-instance-identifier restored-${RDS_TO_RESTORE}-instance-2 \
                            --db-instance-class ${RDS_INSTANCE_TYPE}"

                    echo "=========================== AWS RDS: Wait when Aurora Instance have been created  ==========================="
                    sh "sleep 500"
                    sh "aws rds wait db-instance-available --db-instance-identifier restored-${RDS_TO_RESTORE}-instance-1"
                    sh "aws rds wait db-instance-available --db-instance-identifier restored-${RDS_TO_RESTORE}-instance-2"
                }
                break

            case "restore-rds-instance":
                stage("RDS: Restore RDS Instance"){
                    echo "====================================  AWS RDS: Restore DB Instance  ========================================="
                    sh "aws rds restore-db-instance-from-db-snapshot \
                            --db-instance-identifier restored-${RDS_TO_RESTORE} \
                            --db-snapshot-identifier ${rds_snap} \
                            --publicly-accessible \
                            --engine postgres \
                            --vpc-security-group-ids ${RDS_VPC_SG_ID} \
                            --db-subnet-group-name ${RDS_SUBNET_GROUP_NAME} \
                            --db-parameter-group-name ${RDS_PARAMETER_GROUP_NAME} \
                            --db-instance-class ${RDS_INSTANCE_TYPE}"
                    
                    echo "============================= AWS RDS: Wait when DB Instance have been created =============================="
                    sh "aws rds wait db-instance-available --db-instance-identifier restored-${RDS_TO_RESTORE}"
                }
                break

            case "delete-restored-aurora-cluster":
                stage("RDS: Delete Aurora Cluster") {

                    echo "===========================================   AWS RDS: Delete Aurora Instances    ==========================================="
                    sh "aws rds delete-db-instance --skip-final-snapshot --db-instance-identifier ${RDS_TO_DEL}-instance-1"
                    sh "aws rds delete-db-instance --skip-final-snapshot --db-instance-identifier ${RDS_TO_DEL}-instance-2"

                    echo "===================================  AWS RDS: Wait when Aurora Instances have been deleted =================================="
                    sh "aws rds wait db-instance-deleted --db-instance-identifier ${RDS_TO_DEL}-instance-1"
                    sh "aws rds wait db-instance-deleted --db-instance-identifier ${RDS_TO_DEL}-instance-2"

                    echo "===========================================   AWS RDS: Delete Aurora Cluster    ============================================="
                    sh "aws rds delete-db-cluster --skip-final-snapshot --db-cluster-identifier ${RDS_TO_DEL}"

                }
                break

            case "delete-restored-rds-instance":
                stage("RDS: Delete DB Instance"){

                    echo "=====================================  AWS RDS: Delete DB Instances  ========================================"
                    sh "aws rds delete-db-instance --skip-final-snapshot --db-instance-identifier ${RDS_TO_DEL}"

                    echo "============================  AWS RDS: Wait when DB Instances have been deleted ============================="
                    sh "aws rds wait db-instance-deleted --db-instance-identifie ${RDS_TO_DEL}"
                }
                break
        }
    }
}