/**
 * Builds dependency-free process health for the Bolt transport bootstrap.
 * Actual Socket Mode wiring belongs to the transport implementation task.
 *
 * @param {string} [nodeVersion]
 * @returns {{service: string, status: string}}
 */
export function buildHealth(nodeVersion = process.versions.node) {
  const major = Number.parseInt(nodeVersion.split(".", 1)[0], 10);
  if (!Number.isInteger(major) || major < 20) {
    throw new RangeError("Node.js 20 or newer is required");
  }

  return Object.freeze({service: "uniton-slack-bolt", status: "ok"});
}
