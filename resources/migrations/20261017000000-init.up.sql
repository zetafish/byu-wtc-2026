create table country (
  source text not null, code text not null, name text not null,
  primary key (source, code));
--;;
create table runner (
  source text not null, id text not null, name text not null,
  country text not null, bib text,
  primary key (source, id),
  foreign key (source, country) references country (source, code));
--;;
create table lap (
  source text not null, runner_id text not null, lap int not null,
  status text not null, seconds int,
  updated_at timestamptz not null default now(),
  primary key (source, runner_id, lap),
  foreign key (source, runner_id) references runner (source, id));
--;;
create table fetch_log (
  id bigserial primary key, source text not null,
  started_at timestamptz not null, duration_ms int not null,
  ok boolean not null, error text, laps int, rejected int);
