aws sso login
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

aws ssm start-session --target i-06d94537d79a661d8 --region ap-southeast-2 --document-name AWS-StartPortForwardingSession --parameters portNumber=5432,localPortNumber=5433
exit $LASTEXITCODE
