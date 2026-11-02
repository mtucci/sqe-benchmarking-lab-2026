# SQE Benchmarking Lab 2026

In this lab, you will measure the same risk-scoring function in three ways:

- **Microbenchmark:** call the Java function directly with JMH.
- **Component benchmark:** send requests to the risk service with k6.
- **End-to-end benchmark:** send requests through the complete application with k6.

Each implementation has five JVM forks or five separately prepared HTTP runs. Run the supplied experiments, change their parameters, and use the dashboard to compare results. You do not need to write benchmark code or submit any work.

## Start the lab

You need **Docker and a browser**. On Windows/macOS, use Docker Desktop with Linux containers. Run these commands in your host terminal:

```sh
docker pull mtucci/sqe-benchmarking-lab:2026
docker run -d --name sqe-lab --cpus=2 --memory=3g -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8090 -v sqe-lab-data:/lab/data mtucci/sqe-benchmarking-lab:2026
docker exec -it sqe-lab bash
```

Inside the container, run:

```sh
lab-ready
```

Open the application at **http://localhost:8080** and the analysis dashboard at **http://localhost:8081** in your browser. Then follow the [benchmarking exercises](benchmarking/README.md) to run the experiments and analyze your results. All benchmark commands run inside the container shell, on Windows, Linux and macOS. Java, JMH, k6, Python and the application services are already installed.

Results are saved in the `sqe-lab-data` volume. When you finish, leave the container shell with `exit`, then run `docker stop sqe-lab` in your host terminal. You can resume with `docker start sqe-lab` and `docker exec -it sqe-lab bash`.

## Understand what you are measuring

The application is a small online shop split into five services. It reads prices and customer information and computes an order's risk score.

The baseline implementation scans every risk rule. The optimized implementation selects rules for the shipping country first. Both return the same answer. At 100000 rules across 20 countries, a DE order has 5000 relevant rules.

Compare baseline and optimized runs at the same rule count and client count. Inspect distributions, time series and warm-up behaviour. Confidence intervals and tests use all individual samples retained after removing warm-up separately from each run/fork, assuming sample independence. Does the improvement in the function produce the same improvement in the complete request?

## Explore the code

| Reference | What you will find |
| --- | --- |
| [Benchmarking exercises](benchmarking/README.md) | Commands and instructions for all three experiments and the results dashboard. |
| [Source guide](benchmarking/SOURCE-GUIDE.md) | The risk function, benchmark wrapper, HTTP entry points and analysis code. |
| [Toy application](toy-example-sqe/README.md) | Services, APIs, configuration and application use cases. |
| [Architecture](benchmarking/benchmark-architecture.png) | Components and benchmark entry points. |
| [Sequence diagram](benchmarking/benchmark-scopes.png) | The request path timed by each benchmark. |

The `benchmarking/` folder contains the supplied experiments and analysis tools. The `toy-example-sqe/` folder contains the complete application source. You can read the code here without installing anything beyond the tools needed to run the lab.
