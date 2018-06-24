package stork.core.handlers;

import stork.core.server.*;

public class MkdirHandler extends Handler<EndpointRequest> {
  public void handle(EndpointRequest req) {
    System.out.println(req);
    req.assertLoggedIn();
    req.assertMayChangeState();
    req.resolve().mkdir().promise(req);
  }
}
