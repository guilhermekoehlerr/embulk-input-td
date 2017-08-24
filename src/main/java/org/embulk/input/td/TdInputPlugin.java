package org.embulk.input.td;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.inject.Inject;
import com.treasuredata.client.ProxyConfig;
import com.treasuredata.client.TDClient;
import com.treasuredata.client.TDClientBuilder;
import com.treasuredata.client.model.TDJob;
import com.treasuredata.client.model.TDJobRequest;
import com.treasuredata.client.model.TDJobSummary;
import com.treasuredata.client.model.TDResultFormat;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigInject;
import org.embulk.config.TaskReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.input.td.writer.BooleanValueWriter;
import org.embulk.input.td.writer.DoubleValueWriter;
import org.embulk.input.td.writer.LongValueWriter;
import org.embulk.input.td.writer.StringValueWriter;
import org.embulk.input.td.writer.ValueWriter;
import org.embulk.spi.BufferAllocator;
import org.embulk.spi.Column;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.PageBuilder;
import org.embulk.spi.PageOutput;
import org.embulk.spi.Schema;
import org.embulk.spi.type.Type;
import org.embulk.spi.type.Types;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;
import org.slf4j.Logger;

public class TdInputPlugin
        implements InputPlugin
{
    public interface PluginTask
            extends Task
    {
        @Config("apikey")
        public String getApiKey();

        @Config("endpoint")
        @ConfigDefault("\"api.treasuredata.com\"")
        public String getEndpoint();

        @Config("use_ssl")
        @ConfigDefault("true")
        public boolean getUseSsl();

        @Config("http_proxy")
        @ConfigDefault("null")
        public Optional<HttpProxyTask> getHttpProxy();

        // TODO timeout
        // TODO query, database

        @Config("query")
        @ConfigDefault("null")
        public Optional<String> getQuery();

        @Config("database")
        @ConfigDefault("null")
        public Optional<String> getDatabase();

        @Config("job_id")
        @ConfigDefault("null")
        public Optional<String> getJobId();

        @Config("stop_on_invalid_record")
        @ConfigDefault("false")
        public boolean getStopOnInvalidRecord();

        // TODO column_options

        @ConfigInject
        BufferAllocator getBufferAllocator();
    }

    public interface HttpProxyTask
            extends Task
    {
        @Config("host")
        public String getHost();

        @Config("port")
        public int getPort();

        @Config("use_ssl")
        @ConfigDefault("false")
        public boolean getUseSsl();

        @Config("user")
        @ConfigDefault("null")
        public Optional<String> getUser();

        @Config("password")
        @ConfigDefault("null")
        public Optional<String> getPassword();
    }

    private final Logger log;

    @Inject
    public TdInputPlugin()
    {
        this.log = Exec.getLogger(this.getClass());
    }

    @Override
    public ConfigDiff transaction(final ConfigSource config, final InputPlugin.Control control)
    {
        final PluginTask task = config.loadConfig(PluginTask.class);
        try (final TDClient client = newTDClient(task)) {
            final TDJob job = getTDJob(task, client);

            final Optional<String> jobResultSchema = job.getResultSchema();
            if (!jobResultSchema.isPresent()) {
                throw new ConfigException(String.format("Not found result schema of job %s", job.getJobId()));
            }

            final Schema inputSchema = convertSchema(job.getType(), toJsonNode(jobResultSchema.get()));
            newValueWriters(inputSchema); // validate if value writers can be created according to the input schema

            final TaskSource taskSource = task.dump().set("job_id", job.getJobId()); // overwrite job_id
            return resume(taskSource, inputSchema, 1, control);
        }
    }

    private TDClient newTDClient(final PluginTask task)
    {
        final TDClientBuilder builder = TDClient.newBuilder();
        builder.setApiKey(task.getApiKey());
        builder.setEndpoint(task.getEndpoint());
        builder.setUseSSL(task.getUseSsl());

        final Optional<ProxyConfig>proxyConfig = newProxyConfig(task.getHttpProxy());
        if (proxyConfig.isPresent()) {
            builder.setProxy(proxyConfig.get());
        }

        return builder.build();
    }

    private Optional<ProxyConfig> newProxyConfig(final Optional<HttpProxyTask> task)
    {
        // This plugin searches http proxy settings and configures them to TDClient. The order of proxy setting searching is:
        // 1. System properties
        // 2. http_proxy config option provided by this plugin

        final Properties props = System.getProperties();
        if (props.containsKey("http.proxyHost") || props.containsKey("https.proxyHost")) {
            final boolean useSsl = props.containsKey("https.proxyHost");
            final String proto = !useSsl ? "http" : "https";
            final String host = props.getProperty(proto + ".proxyHost");
            final int port = Integer.parseInt(props.getProperty(proto + ".proxyPort", !useSsl ? "80" : "443"));
            final Optional<String> user = Optional.fromNullable(props.getProperty(proto + ".proxyUser"));
            final Optional<String> password = Optional.fromNullable(props.getProperty(proto + ".proxyPassword"));
            return Optional.of(new ProxyConfig(host, port, useSsl, user, password));
        }
        else if (task.isPresent()) {
            final HttpProxyTask proxyTask = task.get();
            return Optional.of(new ProxyConfig(proxyTask.getHost(), proxyTask.getPort(), proxyTask.getUseSsl(),
                    proxyTask.getUser(), proxyTask.getPassword()));
        }
        else {
            return Optional.absent();
        }
    }

    private TDJob getTDJob(final PluginTask task, final TDClient client)
    {
        final String jobId;
        if (!task.getJobId().isPresent()) {
            if (!task.getQuery().isPresent() || !task.getDatabase().isPresent()) {
                throw new ConfigException("Must specify both of 'query' and 'database' options if 'job_id' option is not used.");
            }
            jobId = submitJob(task, client);
        }
        else {
            jobId = task.getJobId().get();
        }

        waitJobCompletion(task, client, jobId);
        return client.jobInfo(jobId);
    }

    private String submitJob(final PluginTask task, final TDClient client)
    {
        final String query = task.getQuery().get();
        final String database = task.getDatabase().get();

        log.info(String.format(Locale.ENGLISH, "Submit a query for database '%s': %s", database, query));
        final String jobId = client.submit(TDJobRequest.newPrestoQuery(database, query));
        log.info(String.format(Locale.ENGLISH, "Job %s is queued.", jobId));
        return jobId;
    }

    private void waitJobCompletion(final PluginTask task, final TDClient client, String jobId)
    {
        TDJobSummary js;
        long waitTime = 5 * 1000; // 5 secs

        // wait for job finish
        log.info(String.format(Locale.ENGLISH, "Confirm that job %s finished", jobId));
        while (true) {
            js = client.jobStatus(jobId);
            if (js.getStatus().isFinished()) {
                break;
            }

            log.debug("Wait for job finished");
            try {
                Thread.sleep(waitTime);
            }
            catch (InterruptedException ignored) {
            }
        }

        // confirm if the job status is 'success'
        if (js.getStatus() != TDJob.Status.SUCCESS) {
            throw new ConfigException(String.format(Locale.ENGLISH, "Cannot download job result because the job was '%s'.", js.getStatus()));
        }
    }

    private static JsonNode toJsonNode(final String schema)
    {
        try {
            return new ObjectMapper().readTree(schema);
        }
        catch (IOException e) {
            throw new ConfigException(String.format(Locale.ENGLISH, "Failed to parse job result schema as JSON: %s", schema));
        }
    }

    private Schema convertSchema(final TDJob.Type jobType, final JsonNode from)
    {
        final Schema.Builder schema = new Schema.Builder();
        final ArrayNode a = (ArrayNode) from;
        for (int i = 0; i < a.size(); i++) {
            final ArrayNode column = (ArrayNode)a.get(i);
            final String name = column.get(0).asText();
            final Type type = convertColumnType(jobType, column.get(1).asText());
            schema.add(name, type);
        }
        return schema.build();
    }

    private Type convertColumnType(final TDJob.Type jobType, final String from)
    {
        switch (jobType) {
        case PRESTO:
            return convertPrestoColumnType(from);
        case HIVE:
        default:
            throw new ConfigException(String.format(Locale.ENGLISH, "Unsupported job type '%s'. Supported types are [presto].", jobType)); // TODO hive
        }
    }

    private Type convertPrestoColumnType(final String from)
    {
        final String t = from.toUpperCase(Locale.ENGLISH);
        if (t.equals("BOOLEAN")) {
            return Types.BOOLEAN;
        }
        else if (t.equals("BIGINT")) {
            return Types.LONG;
        }
        else if (t.equals("DOUBLE") || t.equals("DECIMAL") || t.startsWith("DECIMAL")) {
            return Types.DOUBLE;
        }
        else if (t.equals("VARCHAR") || t.startsWith("VARCHAR")) {
            return Types.STRING;
        }
        else {
            throw new ConfigException(String.format(Locale.ENGLISH, "Unsupported presto type '%s'", from)); // TODO other types
        }
    }

    @Override
    public ConfigDiff resume(
            final TaskSource taskSource,
            final Schema schema,
            final int taskCount,
            final InputPlugin.Control control)
    {
        control.run(taskSource, schema, taskCount);
        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(
            final TaskSource taskSource,
            final Schema schema,
            final int taskCount,
            final List<TaskReport> successTaskReports)
    {
        // do nothing
    }

    @Override
    public TaskReport run(
            final TaskSource taskSource,
            final Schema schema,
            final int taskIndex,
            final PageOutput output)
    {
        final PluginTask task = taskSource.loadTask(PluginTask.class);
        final BufferAllocator allocator = task.getBufferAllocator();
        final ValueWriter[] writers = newValueWriters(schema);
        final String jobId = taskSource.get(String.class, "job_id");
        final boolean stopOnInvalidRecord = task.getStopOnInvalidRecord();

        try (final PageBuilder pageBuilder = new PageBuilder(allocator, schema, output);
                final TDClient client = newTDClient(task)) {
            client.jobResult(jobId, TDResultFormat.MESSAGE_PACK_GZ, new Function<InputStream, Void>() {
                @Override
                public Void apply(InputStream input)
                {
                    try (final MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(new GZIPInputStream(input))) {
                        while (unpacker.hasNext()) {
                            try {
                                final Value v;
                                try {
                                    v = unpacker.unpackValue();
                                }
                                catch (IOException e) {
                                    throw new InvalidRecordException("Cannot unpack value", e);
                                }

                                if (!v.isArrayValue()) {
                                    throw new InvalidRecordException(String.format(Locale.ENGLISH, "Must be array value: ", v.toString()));
                                }

                                final ArrayValue record = v.asArrayValue();
                                if (record.size() != schema.size()) {
                                    throw new InvalidRecordException(String.format(Locale.ENGLISH, "The size (%d) of the record is invalid", record.size()));
                                }

                                // write records to the page
                                for (int i = 0; i < writers.length; i++) {
                                    writers[i].write(record.get(i), pageBuilder);
                                }

                                pageBuilder.addRecord();
                            }
                            catch (InvalidRecordException e) {
                                if (stopOnInvalidRecord) {
                                    throw new DataException(String.format(Locale.ENGLISH, "Invalid record (%s)", e.getMessage()), e);
                                }
                                log.warn(String.format(Locale.ENGLISH, "Skipped record (%s)", e.getMessage()));
                            }
                        }
                    }
                    catch (IOException e) {
                        throw Throwables.propagate(e);
                    }

                    return null;
                }
            });

            pageBuilder.finish();
        }

        return Exec.newTaskReport();
    }

    private ValueWriter[] newValueWriters(final Schema schema)
    {
        final ValueWriter[] writers = new ValueWriter[schema.size()];
        for (int i = 0; i < schema.size(); i++) {
            writers[i] = newValueWriter(schema.getColumn(i));
        }
        return writers;
    }

    private ValueWriter newValueWriter(final Column column)
    {
        final Type type = column.getType();
        if (type.equals(Types.BOOLEAN)) {
            return new BooleanValueWriter(column);
        }
        else if (type.equals(Types.DOUBLE)) {
            return new DoubleValueWriter(column);
        }
        else if (type.equals(Types.JSON)) {
            throw new ConfigException(String.format(Locale.ENGLISH, "Unsupported column type (%s:%s)", column.getName(), type)); // TODO
        }
        else if (type.equals(Types.LONG)) {
            return new LongValueWriter(column);
        }
        else if (type.equals(Types.STRING)) {
            return new StringValueWriter(column);
        }
        else if (type.equals(Types.TIMESTAMP)) {
            throw new ConfigException(String.format(Locale.ENGLISH, "Unsupported column type (%s:%s)", column.getName(), type)); // TODO
        }
        else {
            throw new ConfigException(String.format(Locale.ENGLISH, "Unsupported column type (%s:%s)", column.getName(), type)); // TODO
        }
    }

    @Override
    public ConfigDiff guess(final ConfigSource config)
    {
        return Exec.newConfigDiff(); // do nothing
    }

    static class InvalidRecordException
            extends RuntimeException
    {
        InvalidRecordException(final String cause) {
            super(cause);
        }

        InvalidRecordException(final String cause, final Throwable t) {
            super(cause, t);
        }
    }
}
