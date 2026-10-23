package com.rag2agent.bootstrap.security;

import com.rag2agent.bootstrap.config.SecurityProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

@Component
public class InternalNetworkPolicy {

    private final SecurityProperties properties;

    public InternalNetworkPolicy(SecurityProperties properties) {
        this.properties = properties;
    }

    public boolean contains(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return false;
        }
        String normalized = remoteAddress.startsWith("::ffff:")
                ? remoteAddress.substring("::ffff:".length())
                : remoteAddress;
        try {
            InetAddress address = InetAddress.getByName(normalized);
            for (String cidr : properties.getInternalCidrs()) {
                if (matches(address, cidr)) {
                    return true;
                }
            }
        } catch (UnknownHostException ignored) {
            return false;
        }
        return false;
    }

    private boolean matches(InetAddress address, String cidr) {
        String[] parts = cidr == null ? new String[0] : cidr.trim().split("/", 2);
        if (parts.length != 2) {
            return false;
        }
        try {
            InetAddress network = InetAddress.getByName(parts[0]);
            byte[] actual = address.getAddress();
            byte[] expected = network.getAddress();
            if (actual.length != expected.length) {
                return false;
            }
            int prefix = Integer.parseInt(parts[1]);
            if (prefix < 0 || prefix > actual.length * 8) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((actual[bit / 8] & mask) != (expected[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException ignored) {
            return false;
        }
    }
}
