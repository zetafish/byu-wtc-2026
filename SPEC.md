# Live website to visualize the 2026 backyard ultra world team championship

## Background

During upfront byu i created a live website (see ../upfront-live). Deployed on vercel, free tier.

Featuring

- frontend with nice visualization
- backend 
- redis to keep state on where runners are in the race
- 1m ping to trigger the backend to fetch live data

Upfront had also live trackeres with gpx coordinates and track so we could visulaize where runners were. 

Also where runners were within a loop and if they were in danger of DNF-ing

Edge conditions:
- missed registration of a loop
- runners turning aroung and not finishing a loop
- ...

## WTC 2026

[https://bigsbackyardultra.com/world-team-championship-2026/](roster)

Start 17 oct at 14:00 CEST

Rules are similar to byu:

- Everyone still in the race starts and must finish within the hour

But additionally:

- Runners from a country can continue as long as at least 2 runners are in the race. 

## Data

I dont know how i will be able to retrieve data

maybe this page: https://bigsbackyardultra.com/2024-world-team-championship-results/
maybe google doc

assume minimal data like
- per hour
  - time of the runner

We will not have gpx and even if we did, every country has their own loop

Maybe we can have for some countries feeds that have more detail or are more accurate than the global overal data sheets

## Architecture

The upfront-live was a bit silly. I want a more robust implementation. Also we must think about scalability as the website will be visited not only by NL but all over the world so more traffic (latency out of scope)

- postgres for durable data - i want to store for each hour the times of the runners, this will allow me to do usefull statistics
- redis for cache
- cache is to offload the database
- clojure backend
- clojurescript frontend
- backend will have an inbuilt scheduler that fetches hourly data. be prepared for timeouts or http failures

## Visualizations

backends has endpoints to retrieve visualization. standard json. think of prom queries and responses and grafana visualizing as timeseries, barcharts etc.

frontend think of grafana like. Reusable code so make it easy to add visualisation

### Ideas

- histogram X=active runenrs Y=number of countries
- attrition rate
- per runner graph of their laptime. As minigraph in table of selected runners, pop out when clicked on
- select countreis
- compare countrues
- star runners


## Deployment

Vercel or maybe others. What fits in free tier
