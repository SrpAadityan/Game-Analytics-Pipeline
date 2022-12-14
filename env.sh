# Copyright 2019 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Project Name
if [ -z $GOOGLE_CLOUD_PROJECT ]; then
    GOOGLE_CLOUD_PROJECT="game-analytics-pipeline"
fi
# Pub/Sub topic
export MY_TOPIC="raw-events"
# GCS Bucket
export MY_BUCKET="game-analytics-pipeline-bucket"
# Avro file GCS output path
export AVRO_OUT="gs://game-analytics-pipeline-bucket/avro/"
# Region for Cloud Pub/Sub and Cloud Dataflow
export REGION="us-central1"
# Region for BigQuery
export BQ_REGION="US"
# BigQuery dataset name
export BQ_DATASET="tracking"
# BigQuery table name
export BQ_TABLE="raw_events"
