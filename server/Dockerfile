FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
# police DejaVu : accents, œ, flèches dans la fiche PDF (les polices de base du PDF s'arrêtent au latin-1)
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY shc ./shc
RUN useradd --system --uid 10001 shc && mkdir /data && chown shc /data
USER shc
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD python -m shc.healthcheck
CMD ["python", "-m", "shc"]
