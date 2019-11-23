# trade-history
Microservice that keeps a detailed history of all stock trades

 ### Prerequisites for OCP Deployment
 This project requires five secrets: `jwt`, `mongodbenv`, `kafka-consumer`, `kafka-keystore`, and `kafka-extra`.
 
 ### Build and Deploy to OCP
To build `trade-history` clone this repo and run:
```
cd templates

oc create -f trade-history-liberty-projects.yaml

oc create -f trade-history-liberty-deploy.yaml -n trade-history-liberty-dev
oc create -f trade-history-liberty-deploy.yaml -n trade-history-liberty-stage
oc create -f trade-history-liberty-deploy.yaml -n trade-history-liberty-prod

oc new-app trade-history-liberty-deploy -n trade-history-liberty-dev
oc new-app trade-history-liberty-deploy -n trade-history-liberty-stage
oc new-app trade-history-liberty-deploy -n trade-history-liberty-prod

oc create -f trade-history-liberty-build.yaml -n trade-history-liberty-build

oc new-app trade-history-liberty-build -n trade-history-liberty-build

```
