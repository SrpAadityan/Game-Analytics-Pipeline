package pipeline;

import java.io.Serializable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.AvroIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.CreateDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.AfterWatermark;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableReference;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
/**
 * A sample game analytics architecture for processing events from a PubSub topic
 * and saving the events to a table in BigQuery and AVRO files on google storage (gs).
 *
 * Events published to the PubSub topic should be JSON strings with the following fields:
 *  eventType: the name of the event (e.g. Session, MatchStart, LevelUp)
 *  eventVersion: specifies the schema version of the event (e.g. V1)
 *
 * Additional attributes can be added to events, but this will not be parsed out by
 * the raw event pipeline. The pipeline adds an additional attribute to the event
 * (server_time), which is the timestamp when the server processed the event.
 */
public class RawEventPipeline {

  /** The topic to subscribe to for game events */
  private static String topic = "projects/game-analytics-pipeline/topics/raw-events";

  /** Provides an interface for setting the GCS temp location and streaming mode */
  interface Options extends PipelineOptions, Serializable {
    String getTempLocation();
    void setTempLocation(String value);

    boolean isStreaming();
    void setStreaming(boolean value);
  }

  /**
   * Creates a streaming dataflow pipeline that saves events to AVRO and bigquery.
   */
  public static void main(String[] args) {

    // event schema for the raw_events table
    List<TableFieldSchema> fields = new ArrayList();
    fields.add(new TableFieldSchema().setName("eventType").setType("STRING"));
    fields.add(new TableFieldSchema().setName("eventVersion").setType("STRING"));
    fields.add(new TableFieldSchema().setName("serverTime").setType("STRING"));
    fields.add(new TableFieldSchema().setName("message").setType("STRING"));
    TableSchema schema = new TableSchema().setFields(fields);

    // BQ output table information
    TableReference table = new TableReference();
    table.setDatasetId("tracking");
    table.setProjectId("game-analytics-pipeline");
    table.setTableId("raw_events");

    // output format for serverTime attribute
    final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // set up pipeline options
    Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
    options.setStreaming(true);
    Pipeline pipeline = Pipeline.create(options);

    // read game events from PubSub
    PCollection<PubsubMessage> events = pipeline
            .apply(PubsubIO.readMessages().fromTopic(topic));

    // parse the PubSub events and create rows to insert into BigQuery
    events.apply("To Table Rows", new PTransform<PCollection<PubsubMessage>, PCollection<TableRow>>() {

      public PCollection<TableRow> expand(PCollection<PubsubMessage> input) {
        return input.apply("To Predictions", ParDo.of(new DoFn<PubsubMessage, TableRow>() {

          @ProcessElement
          public void processElement(ProcessContext c) throws Exception {
            String message = new String(c.element().getPayload());

            // parse the json message for attributes
            JsonObject jsonObject = new JsonParser().parse(message).getAsJsonObject();
            String eventType = jsonObject.get("eventType").getAsString();
            String eventVersion = jsonObject.get("eventVersion").getAsString();
            String serverTime = dateFormat.format(new Date());

            // create and output the table row
            TableRow record = new TableRow();
            record.set("eventType", eventType);
            record.set("eventVersion", eventVersion);
            record.set("serverTime", serverTime);
            record.set("message", message);
            c.output(record);
          }
        }));
      }
    })
    // stream the events to Big Query
    .apply("To BigQuery",BigQueryIO.writeTableRows()
            .to(table)
            .withSchema(schema)
            .withCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
            .withWriteDisposition(WriteDisposition.WRITE_APPEND));

    // AVRO output portion of the pipeline
    events
      .apply("To String", ParDo.of(new DoFn<PubsubMessage, String>() {
        @ProcessElement
        public void processElement(ProcessContext c) throws Exception {
          String message = new String(c.element().getPayload());
          c.output(message);
        }
      }))

      // Batch events into 5 minute windows
      .apply("Batch Events", Window.<String>into(
              FixedWindows.of(Duration.standardMinutes(5)))
              .triggering(AfterWatermark.pastEndOfWindow())
              .discardingFiredPanes()
              .withAllowedLateness(Duration.standardMinutes(5)))

      // Save the events in ARVO format
      .apply("To AVRO", AvroIO.write(String.class)
              .to("gs://game-analytics-pipeline-bucket/avro/raw-events.avro")
              .withWindowedWrites()
              .withNumShards(8)
              .withSuffix(".avro"));

    // run the pipeline
    pipeline.run();
  }
}
