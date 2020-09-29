package org.testcontainers.containers;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.tarantool.driver.StandaloneTarantoolClient;
import io.tarantool.driver.TarantoolClient;
import io.tarantool.driver.TarantoolClientConfig;
import io.tarantool.driver.TarantoolServerAddress;
import io.tarantool.driver.auth.SimpleTarantoolCredentials;
import io.tarantool.driver.auth.TarantoolCredentials;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sets up a Tarantool instance and provides API for configuring it.
 *
 * @author Alexey Kuzin
 */
public class TarantoolContainer extends GenericContainer<TarantoolContainer> {

    public static final String TARANTOOL_IMAGE = "tarantool/tarantool";
    public static final String DEFAULT_IMAGE_VERSION = "2.x-centos7";

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 3301;
    private static final String API_USER = "api_user";
    private static final String API_PASSWORD = "secret";
    private static final TarantoolLogLevel LOG_LEVEL = TarantoolLogLevel.VERBOSE;
    private static final Integer MEMTX_MEMORY = 128 * 1024 * 1024; // 128 Mb in bytes
    private static final String SCRIPT_RESOURCE_DIRECTORY = "org/testcontainers/containers";
    private static final String SCRIPT_FILENAME = "server.lua";

    private static final String INSTANCE_DIR = "/app";

    private String username = API_USER;
    private String password = API_PASSWORD;
    private String host = DEFAULT_HOST;
    private Integer port = DEFAULT_PORT;
    private TarantoolLogLevel logLevel = LOG_LEVEL;
    private Integer memtxMemory = MEMTX_MEMORY;
    private String directoryResourcePath = getClass().getClassLoader().getResource(SCRIPT_RESOURCE_DIRECTORY).getPath();
    private String scriptFileName = SCRIPT_FILENAME;

    public TarantoolContainer() {
        this(String.format("%s:%s", TARANTOOL_IMAGE, DEFAULT_IMAGE_VERSION));
    }

    public TarantoolContainer(String dockerImageName) {
        super(dockerImageName);
    }

    @Override
    public String getHost() {
        return host;
    }

    /**
     * Get the Tarantool server exposed port for connecting the client to
     *
     * @return a port
     */
    public int getPort() {
        return getMappedPort(port);
    }

    /**
     * Get the Tarantool user name for connecting the client with
     *
     * @return a user name
     */
    public String getUsername() {
        return username;
    }

    /**
     * Get the Tarantool user password for connecting the client with
     *
     * @return a user password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Specify the username for connecting to Tarantool with.
     * Warning! This user must be created on Tarantool instance startup, e.g. specified in the startup script.
     *
     * @param username the client user name
     * @return this container instance
     */
    public TarantoolContainer withUsername(String username) {
        checkNotRunning();
        this.username = username;
        return this;
    }

    /**
     * Specify the password for the specified user for connecting to Tarantool with.
     * Warning! This user must be created on Tarantool instance startup, e.g. specified in the startup script,
     * together with setting the password.
     *
     * @param password the client user password
     * @return this container instance
     */
    public TarantoolContainer withPassword(String password) {
        checkNotRunning();
        this.password = password;
        return this;
    }

    /**
     * Change the log_level setting on the Tarantool instance
     *
     * @param logLevel new log_level value
     * @return this container instance
     */
    public TarantoolContainer withLogLevel(TarantoolLogLevel logLevel) {
        this.logLevel = logLevel;
        if (isRunning()) {
            try {
                executeCommand(logLevel.toCommand()).get();
            } catch (Exception e) {
                logger().error(String.format("Failed to set log_level to %s", logLevel.toString()), e);
            }
        }
        return this;
    }

    /**
     * Change the memtx_memory setting on the Tarantool instance
     *
     * @param memtxMemory new memtx_memory value, must be greater than 0
     * @return this container instance
     */
    public TarantoolContainer withMemtxMemory(Integer memtxMemory) {
        if (memtxMemory <= 0) {
            throw new RuntimeException(
                    String.format("The specified memtx_memory value must be >= 0, but was %d", memtxMemory));
        }
        this.memtxMemory = memtxMemory;
        if (isRunning()) {
            try {
                executeCommand(String.format("box.cfg{memtx_memory=%d}", memtxMemory)).get();
            } catch (Exception e) {
                logger().error(String.format("Failed to set memtx_memory to %d", memtxMemory), e);
            }
        }
        return this;
    }

    /**
     * Specify a directory in the classpath resource which will be mounted to the container. The specified
     * directory will be mapped to the directory "/app".
     *
     * @param directoryResourcePath classpath resource directory full path
     * @return this container instance
     */
    public TarantoolContainer withDirectoryBinding(String directoryResourcePath) {
        checkNotRunning();
        this.directoryResourcePath = getClass().getClassLoader().getResource(directoryResourcePath).getPath();
        if (this.directoryResourcePath == null) {
            throw new IllegalArgumentException(
                    String.format("No resource path found for the specified resource %s", directoryResourcePath));
        }
        return this;
    }

    /**
     * Specify the server init script file name
     *
     * @param scriptFileName script file name, relative to the mounted script resource directory
     * @return this container instance
     * @see #withDirectoryBinding(String)
     */
    public TarantoolContainer withScriptFileName(String scriptFileName) {
        checkNotRunning();
        this.scriptFileName = scriptFileName;
        return this;
    }

    /**
     * Checks if already running and if so raises an exception to prevent too-late setters.
     */
    protected void checkNotRunning() {
        if (isRunning()) {
            throw new IllegalStateException("This option can be changed only before the container is running");
        }
    }

    protected WaitStrategy tarantoolWaitStrategy() {
        return Wait.forLogMessage(".*entering the event loop.*", 1);
    }

    @Override
    protected void configure() {

        withFileSystemBind(directoryResourcePath, INSTANCE_DIR, BindMode.READ_WRITE);
        withExposedPorts(port);
        withCommand("tarantool", Paths.get(INSTANCE_DIR, scriptFileName).toString());

        waitingFor(tarantoolWaitStrategy());
    }

    @Override
    protected void containerIsStarting(InspectContainerResponse containerInfo) {
        logger().info("Tarantool server is starting");
    }

    @Override
    protected void containerIsStarted(InspectContainerResponse containerInfo, boolean reused) {
        super.containerIsStarted(containerInfo, reused);

        withMemtxMemory(memtxMemory);
        withLogLevel(logLevel);

        logger().info("Tarantool server is listening at {}:{}", getHost(), getPort());
    }

    /**
     * Execute a local script in the Tarantool instance. The path must be classpath-relative.
     * `dofile()` function is executed internally, so possible exceptions will be caught as the client exceptions.
     *
     * @param scriptResourcePath the classpath resource path to a script
     * @return script execution result
     * @throws Exception if failed to connect to the instance or execution fails
     */
    public CompletableFuture<List<Object>> executeScript(String scriptResourcePath) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("Cannot execute scripts in stopped container");
        }
        String scriptName = Paths.get(scriptResourcePath).getFileName().toString();
        String containerPath = Paths.get(INSTANCE_DIR, scriptName).toString();
        this.copyFileToContainer(
                MountableFile.forClasspathResource(scriptResourcePath), containerPath);
        return executeCommand(String.format("dofile('%s')", containerPath));
    }

    /**
     * Execute a command in the Tarantool instance. Example of a command: `return 1 + 2, 'foo'`
     *
     * @param command a valid Lua command or a sequence of Lua commands
     * @param arguments command arguments
     * @return command execution result
     * @throws Exception if failed to connect to the instance or execution fails
     */
    public CompletableFuture<List<Object>> executeCommand(String command, Object... arguments) throws Exception {
        if (!isRunning()) {
            throw new IllegalStateException("Cannot execute commands in stopped container");
        }

        TarantoolCredentials credentials = new SimpleTarantoolCredentials(getUsername(), getPassword());
        TarantoolClientConfig config = TarantoolClientConfig.builder()
                .withCredentials(credentials).build();
        TarantoolServerAddress address = new TarantoolServerAddress(getHost(), getPort());
        try (TarantoolClient client = new StandaloneTarantoolClient(config, address)) {
            return client.eval(command, Arrays.asList(arguments));
        } catch (Exception e) {
            logger().error("Failed to execute command '{}': {}", command, e.getMessage());
            throw e;
        }
    }
}
