package ai.jgp.gha.dataproduct;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a versioned ZIP from a product YAML file and its referenced
 * contract files. The ZIP contains:
 * <ul>
 *   <li>{productId}-v{productVersion}.odps.yaml</li>
 *   <li>{contractId}-v{portVersion}.odcs.yaml for each output port</li>
 * </ul>
 */
public class ZipBuilder {

    private static final Logger log = Logger.getLogger(ZipBuilder.class.getName());

    /**
     * Builds a ZIP from a product YAML file. Parses the product to find
     * referenced contracts via outputPorts, locates the contract files in
     * the same directory, and packages everything with versioned filenames.
     *
     * @param productYamlPath path to the .odps.yaml file
     * @return path to the temporary ZIP file
     */
    public static Path buildFromProduct(String productYamlPath) throws IOException {
        Path productPath = Path.of(productYamlPath);
        Path dir = productPath.getParent();
        if (dir == null) {
            dir = Path.of(".");
        }

        YAMLMapper yamlMapper = new YAMLMapper();
        String productContent = Files.readString(productPath);
        JsonNode root = yamlMapper.readTree(productContent);

        String productId = root.path("id").asText();
        String productVersion = normalizeVersion(root.path("version").asText());

        System.out.println("Building versioned ZIP for product: " + productId + " v" + productVersion);

        Path zipFile = Files.createTempFile("data-products-", ".zip");
        zipFile.toFile().deleteOnExit();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile.toFile()))) {
            // 1. Add the product itself
            String productEntry = productId + "-v" + productVersion + ".odps.yaml";
            addEntry(zos, productEntry, productContent.getBytes());
            System.out.println("  + " + productEntry);

            // 2. Add contracts from output ports
            JsonNode outputPorts = root.path("outputPorts");
            if (outputPorts.isArray()) {
                for (JsonNode port : outputPorts) {
                    JsonNode contractIdNode = port.path("contractId");
                    JsonNode portVersionNode = port.path("version");
                    JsonNode portNameNode = port.path("name");
                    String contractId = contractIdNode.isMissingNode() ? null : contractIdNode.asText();
                    String portVersion = portVersionNode.isMissingNode() ? null : normalizeVersion(portVersionNode.asText());
                    String portName = portNameNode.isMissingNode() ? "unnamed" : portNameNode.asText();

                    if (contractId == null || contractId.isEmpty()) {
                        log.fine("Skipping output port '" + portName + "' — no contractId");
                        continue;
                    }
                    if (portVersion == null || portVersion.isEmpty()) {
                        log.fine("Skipping output port '" + portName + "' — no version");
                        continue;
                    }

                    // Look for contract file in the same directory
                    Path contractPath = dir.resolve(contractId + ".odcs.yaml");
                    if (!Files.exists(contractPath)) {
                        System.err.println("  Warning: contract file not found: " + contractPath.getFileName());
                        continue;
                    }

                    byte[] contractBytes = Files.readAllBytes(contractPath);
                    String contractEntry = contractId + "-v" + portVersion + ".odcs.yaml";
                    addEntry(zos, contractEntry, contractBytes);
                    System.out.println("  + " + contractEntry + " (from output port '" + portName + "')");
                }
            }
        }

        return zipFile;
    }

    private static void addEntry(ZipOutputStream zos, String name, byte[] data)
            throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /**
     * Normalizes a version string by stripping a leading "v" if present.
     * The caller adds "v" back in the filename, so we store just the number.
     */
    static String normalizeVersion(String version) {
        if (version != null && version.startsWith("v")) {
            return version.substring(1);
        }
        return version;
    }
}
