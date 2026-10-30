package io.github.illuseahashmap.serviceregistry.domain;

import java.io.IOException;

public interface HttpTransport {

    TransportResponse send(OutboundRequest request) throws IOException;
}
