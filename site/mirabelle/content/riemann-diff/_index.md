---
title: Differences with Riemann
weight: 20
chapter: false
---

Mirabelle is heavily inspired by [Riemann](riemann.io), but still have major differences.

## Streams are represented as EDN

In Riemann, streams are function. In Mirabelle, they [compile](/howto/stream/#edn-representation-and-compilation) as EDN.

It means streams configurations can be easily parsed by other systems, passed between systems, verified...

## No global side effects/schedulers

In Mirabelle, **all** actions use the events time as wall clock. It means streams times advance depending of the event they receives.

Because of that, it's easy to reason about (and test) streams: the same inputs always produce the same outputs. It's also possible to have multiple streams with different times running in parallel.

## Various streams can coexist, dynamic streams

You can in Mirabelle create streams which will not receive the events passed to the Mirabelle TCP server by default.

It means you can have some streams for "real time" computation, and some streams for some use cases. Each stream will run independently, will have its own time...

When you send an event to Mirabelle, you can specify the `stream` attribute in order to send the event to a specific stream.

## HTTP API

Mirabelle provides an [HTTP API](/api) which allow you to manage streams dynamically (create, list, get, remove), and to gather information about your system:

- Querying the [Index](/howto/index/) and the current index time
- Pushing events
- Retrieving Mirabelle metrics which are exposed using the Prometheus format.

## Query language

Mirabelle does not use the Riemann query language to query the index. Instead, it uses its [own language](/howto/stream/#filtering-events), which is used everywhere (index queries, `where` action...).

## Clear distinctions streams and I/O

Streams and I/O are configured separatly, and streams references I/O. These components do not have the same lifecycle, and handling I/O is less error prone in Mirabelle, especially during reloads.

## Hot reload

In Riemann, all streams states (time windows for example) all lost during reloads. it's not the case in Mirabelle. Only streams which were modified in the configuration are recreated.